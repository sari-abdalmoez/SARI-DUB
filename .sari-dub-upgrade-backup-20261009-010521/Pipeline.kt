package com.saridub.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class Progress(
    val projectId: String = "", val stage: String = "Idle", val chunk: Int = 0, val chunks: Int = 0,
    val percent: Int = 0, val etaSec: Long = -1, val speedX: Float = 0f, val message: String = "",
    val running: Boolean = false, val error: String? = null, val thermal: Thermal = Thermal.NORMAL
)

object ProcessingState {
    val progress = MutableStateFlow(Progress())
    val playbackMs = MutableStateFlow(0L)
    val paused = AtomicBoolean(false)
    @Volatile var job: Job? = null

    fun cancel() { Native.setCancel(true); job?.cancel() }
}

/** Picks the next chunk: nearest not-yet-ready chunk at/after the playback position, else earliest pending. */
object ChunkScheduler {
    fun next(p: Project, playbackMs: Long, skip: Set<Int>): Int? {
        val pending = (0 until p.chunkCount).filter { it !in p.ready && it !in skip }
        if (pending.isEmpty()) return null
        val cur = (playbackMs / p.chunkMs).toInt()
        return pending.firstOrNull { it >= cur } ?: pending.first()
    }
}

class Pipeline(private val ctx: Context) {
    private val store = (ctx.applicationContext as SariApp).store
    private val thermal = ThermalMonitor(ctx)
    private val mem = MemoryGuard(ctx)
    private var t0 = 0L

    private fun emit(p: Project, stage: String, chunk: Int, pct: Int, mediaSec: Double = 0.0, msg: String = "") {
        val el = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val eta = if (pct in 3..99 && el > 5) (el / pct * (100 - pct)).toLong() else -1L
        ProcessingState.progress.update {
            Progress(p.id, stage, chunk, p.chunkCount, pct.coerceIn(0, 100), eta,
                if (el > 1 && mediaSec > 0) (mediaSec / el).toFloat() else 0f, msg, true, null, thermal.level())
        }
    }

    /** Honours pause, thermal limits and memory pressure; duty-cycles when warm/hot. */
    private suspend fun gate(p: Project, stage: String, pct: Int) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val th = thermal.level()
            val reason = when {
                ProcessingState.paused.get() -> "Paused"
                th == Thermal.CRITICAL -> "Cooling down (thermal critical)"
                mem.low() -> "Waiting for free memory"
                else -> null
            }
            if (reason == null) break
            emit(p, stage, 0, pct, msg = reason)
            delay(1000)
        }
        when (thermal.level()) { Thermal.WARM -> delay(400); Thermal.HOT -> delay(2500); else -> {} }
    }

    suspend fun run(id: String) {
        val p = store.load(id)
        t0 = SystemClock.elapsedRealtime()
        Native.setCancel(false)
        val dir = store.dir(id)
        val pcm = File(dir, "audio.pcm")

        if (p.durationMs <= 0) throw PipelineError("MEDIA", "Unknown media duration")
        val need = Storage.requiredBytes(p.durationMs)
        val free = Storage.freeBytes(dir)
        if (!pcm.exists() && free < need)
            throw PipelineError("STORAGE", "Not enough storage. Working space needed: ${Storage.gb(need)}, available: ${Storage.gb(free)}.")

        // 1. Audio extraction
        if (!p.extractDone || !pcm.exists()) {
            emit(p, "Extracting audio", 0, 1)
            val raw = File(dir, "audio.raw")
            val (rate, ch) = AudioExtractor.decode(ctx, Uri.parse(p.sourceUri), p.audioTrack, raw) { f -> emit(p, "Extracting audio", 0, (f * 12).toInt()) }
            p.rawRate = rate; p.rawCh = ch
            emit(p, "Resampling audio", 0, 12)
            val n = Native.resampleFile(raw.path, rate, ch, pcm.path, PIPE_RATE)
            raw.delete()
            if (n == Native.CANCELLED.toLong()) throw CancellationException("cancelled")
            if (n < 0) throw PipelineError("AUDIO_EXTRACT", "Audio resampling failed (code $n)")
            p.extractDone = true; p.addLog("Audio extracted: ${n / PIPE_RATE}s @ $PIPE_RATE Hz mono"); store.save(p)
        }

        // 2. Analysis: VAD timing snap + voice profiles + diarisation
        if (!p.analysisDone) {
            if (p.segments.isEmpty())
                throw PipelineError("NO_TEXT", "No source dialogue text. This build has no bundled ASR model: import the original-language subtitles (.srt) in Setup.")
            emit(p, "Detecting speech", 0, 15)
            val v = Native.vad(pcm.path, PIPE_RATE, 200, 300) ?: throw PipelineError("VAD", "Speech detection failed or cancelled")
            val intervals = (0 until v.size / 2).map { v[2 * it] to v[2 * it + 1] }
            analyze(p, pcm, intervals)
            p.analysisDone = true; store.save(p)
        }

        // 3. Translation
        if (!p.translateDone) { translate(p); p.translateDone = true; store.save(p) }

        // 4. Dubbing per chunk
        if (p.hasDub) dub(p, pcm, dir)
        emit(p, "Done", p.chunkCount, 100, msg = if (p.hasDub) "All chunks ready" else "Subtitles translated")
    }

    private suspend fun analyze(p: Project, pcm: File, vad: List<Pair<Double, Double>>) {
        val centroids = mutableListOf<MutableList<Float>>()
        fun semis(hz: Float) = (12.0 * ln(hz / 100.0) / ln(2.0)).toFloat()
        fun median(l: List<Float>) = l.sorted()[l.size / 2]
        var last = 0
        p.segments.forEachIndexed { i, s ->
            gate(p, "Analysing voices", 15 + 15 * i / p.segments.size)
            emit(p, "Analysing voices", 0, 15 + 15 * i / p.segments.size)
            // snap inside the subtitle window to actual detected speech (never extend, never destroy the SRT timing)
            val ov = vad.filter { it.second * 1000 > s.srtStartMs && it.first * 1000 < s.srtEndMs }
            if (ov.isNotEmpty()) {
                val a = max(s.srtStartMs, (ov.first().first * 1000).toLong())
                val b = min(s.srtEndMs, (ov.last().second * 1000).toLong())
                if (b - a >= 400) { s.startMs = a; s.endMs = b }
            }
            val prof = Native.voiceProfile(pcm.path, PIPE_RATE, s.startMs / 1000.0, s.endMs / 1000.0)
                ?: throw PipelineError("VOICE_ANALYSIS", "Voice analysis failed or cancelled")
            s.pitchHz = prof[0]
            if (s.pitchHz > 0 && prof[5] > 0.15f) {
                val st = semis(s.pitchHz)
                var best = -1; var bd = Float.MAX_VALUE
                centroids.forEachIndexed { k, c -> val d = abs(st - median(c)); if (d < bd) { bd = d; best = k } }
                last = if (best >= 0 && (bd < 2.5f || centroids.size >= 6)) best else { centroids.add(mutableListOf()); centroids.size - 1 }
                centroids[last].add(st)
            }
            s.speaker = last
        }
        if (centroids.isEmpty()) centroids.add(mutableListOf())
        p.speakers.clear()
        centroids.forEachIndexed { k, c ->
            val hz = if (c.isEmpty()) 0f else (100.0 * 2.0.pow(median(c) / 12.0)).toFloat()
            p.speakers.add(Speaker(k, "Speaker ${k + 1}", hz, k))
        }
        p.addLog("Detected ${p.speakers.size} speaker(s) by pitch clustering")
    }

    private suspend fun translate(p: Project) {
        if (p.srcLang == p.dstLang) { p.segments.forEach { it.translated = it.original }; return }
        if (!TranslationModels.valid(p.srcLang) || !TranslationModels.valid(p.dstLang))
            throw PipelineError("LANG", "Unsupported language pair ${p.srcLang} -> ${p.dstLang}")
        for (l in listOf(p.srcLang, p.dstLang))
            if (!TranslationModels.isDownloaded(l))
                throw PipelineError("MISSING_MODEL", "Translation model '$l' is not installed. Open Settings > Models and download it (one-time, explicit).")
        val tr = SegTranslator(p.srcLang, p.dstLang)
        try {
            p.segments.forEachIndexed { i, s ->
                gate(p, "Translating", 30 + 15 * i / p.segments.size)
                emit(p, "Translating", 0, 30 + 15 * i / p.segments.size)
                if (s.translated.isBlank()) {
                    val key = s.original.lowercase(Locale.ROOT).trim()
                    var t = p.tm[key] ?: tr.translate(s.original)
                    p.glossary.forEach { (src, dst) -> t = t.replace(src, dst, ignoreCase = true) }
                    p.tm[key] = t; s.translated = t
                }
                if (i % 15 == 0) store.save(p)
            }
        } finally { tr.close() }
    }

    private suspend fun dub(p: Project, pcm: File, dir: File) {
        val tts = TtsSynth(ctx)
        try {
            if (!tts.init()) throw PipelineError("TTS_INIT", "System text-to-speech could not start")
            val loc = Locale.forLanguageTag(p.dstLang)
            val voices = tts.voices(loc)
            if (voices.isEmpty())
                throw PipelineError("MISSING_VOICE", "No offline ${loc.displayLanguage} voice installed. Install one in Android Settings > Text-to-speech.")
            val skip = mutableSetOf<Int>()
            val startReady = p.ready.size
            val chunksDir = File(dir, "chunks").also { it.mkdirs() }
            while (true) {
                val c = ChunkScheduler.next(p, ProcessingState.playbackMs.value, skip) ?: break
                try { processChunk(p, c, pcm, dir, chunksDir, tts, voices, startReady) }
                catch (e: CancellationException) { throw e }
                catch (e: PipelineError) { throw e }
                catch (e: Exception) { skip.add(c); p.failed.add(c); p.addLog("Chunk ${c + 1} failed: ${e.message}"); store.save(p) }
            }
            if (p.failed.isNotEmpty() && p.ready.size < p.chunkCount)
                throw PipelineError("CHUNKS_FAILED", "Chunks failed: ${p.failed.map { it + 1 }}. Others are ready; press Start to retry.")
        } finally { tts.shutdown() }
    }

    private suspend fun processChunk(p: Project, c: Int, pcm: File, dir: File, chunksDir: File, tts: TtsSynth,
                                     voices: List<android.speech.tts.Voice>, startReady: Int) {
        val c0 = c * p.chunkMs
        val c1 = min(p.durationMs, c0 + p.chunkMs)
        val segs = p.segments.filter { it.startMs in c0 until c1 && it.translated.isNotBlank() }
        val starts = ArrayList<Double>(); val wavs = ArrayList<String>()
        var syncErrSum = 0L; var syncN = 0
        val pct0 = 45 + 55 * c / max(1, p.chunkCount)
        for ((i, s) in segs.withIndex()) {
            gate(p, "Generating voices", pct0 + 55 / max(1, p.chunkCount) * i / max(1, segs.size))
            emit(p, "Generating voices", c + 1, pct0 + 55 / max(1, p.chunkCount) * i / max(1, segs.size),
                (p.ready.size - startReady) * p.chunkMs / 1000.0, "Chunk ${c + 1}: segment ${i + 1}/${segs.size}")
            val next = p.segments.getOrNull(p.segments.indexOf(s) + 1)?.startMs ?: (s.endMs + 700)
            val f = dubSegment(p, s, next, tts, voices, dir) ?: continue
            starts.add(s.startMs / 1000.0); wavs.add(f.path)
            val d = Native.wavDurationMs(f.path)
            if (d > 0) { syncErrSum += abs(d - (s.endMs - s.startMs)); syncN++ }
        }
        val out = File(chunksDir, "chunk_$c.wav")
        val duck = if (p.mode == "VOICE") 0f else if (p.duck) 0.22f else 0.55f
        val rc = Native.mixChunk(pcm.path, PIPE_RATE, c0 / 1000.0, c1 / 1000.0, starts.toDoubleArray(), wavs.toTypedArray(), out.path, duck)
        if (rc == Native.CANCELLED) throw CancellationException("cancelled")
        if (rc != 0) throw PipelineError("MIX", "Audio mixing failed (code $rc) for chunk ${c + 1}")
        p.ready.add(c); p.failed.remove(c)
        p.addLog("Chunk ${c + 1}/${p.chunkCount} ready; ${segs.size} segments; mean sync error ${if (syncN > 0) syncErrSum / syncN else 0} ms")
        store.save(p)
    }

    /** TTS -> trim -> measure -> (re-synth faster) -> limited WSOLA compression. Returns final WAV or null on TTS failure. */
    private suspend fun dubSegment(p: Project, s: Segment, nextStartMs: Long, tts: TtsSynth,
                                   voices: List<android.speech.tts.Voice>, dir: File): File? {
        val sp = p.speakers.getOrNull(s.speaker) ?: p.speakers.firstOrNull() ?: return null
        val fin = File(dir, "seg/${s.id}_final.wav")
        if (fin.exists()) return fin
        val raw = File(dir, "seg/${s.id}_raw.wav")
        val voice = voices[sp.voiceIndex.mod(voices.size)]
        val base = if (sp.pitchHz > 0) (sp.pitchHz / 150f).toDouble().pow(0.5).toFloat() else 1f
        val pitch = (base * sp.pitchAdjust).coerceIn(0.5f, 2f)
        var rate = sp.rate
        if (!tts.synth(s.translated, voice, pitch, rate, raw)) { p.addLog("TTS failed for segment ${s.id}"); return null }
        var len = Native.stretchWav(raw.path, fin.path, PIPE_RATE, -1, 1f, 1f)
        if (len < 0) { p.addLog("Segment ${s.id}: empty TTS audio"); return null }
        val target = (s.endMs - s.startMs).toInt()
        if (len > target * 1.05 && p.quality != "FAST") {
            rate = (rate * min(1.4f, len.toFloat() / target)).coerceAtMost(2f)
            if (tts.synth(s.translated, voice, pitch, rate, raw)) len = Native.stretchWav(raw.path, fin.path, PIPE_RATE, -1, 1f, 1f).takeIf { it > 0 } ?: len
        }
        val allowed = (max(target.toLong(), min(nextStartMs - s.startMs - 80, target + 700L))).toInt()
        if (len > allowed) Native.stretchWav(fin.path, fin.path, PIPE_RATE, allowed, 0.75f, 1f)
        raw.delete()
        return fin
    }
}

object Exporter {
    fun srt(p: Project, translated: Boolean): String =
        Srt.format(p.segments.map { Triple(it.srtStartMs, it.srtEndMs, if (translated) it.translated.ifBlank { it.original } else it.original) })

    /** Concatenates ready chunk WAVs (all 44-byte-header mono PCM16 at PIPE_RATE) into one WAV. */
    fun concatDub(store: ProjectStore, p: Project, out: java.io.OutputStream) {
        if (p.ready.size < p.chunkCount) throw PipelineError("EXPORT", "Dubbing incomplete: ${p.ready.size}/${p.chunkCount} chunks ready")
        val dir = File(store.dir(p.id), "chunks")
        val files = (0 until p.chunkCount).map { File(dir, "chunk_$it.wav") }
        val total = files.sumOf { it.length() - 44 }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + total).toInt()).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1.toShort()).putShort(1.toShort()).putInt(PIPE_RATE).putInt(PIPE_RATE * 2).putShort(2.toShort()).putShort(16.toShort())
            .put("data".toByteArray()).putInt(total.toInt())
        out.write(h.array())
        val buf = ByteArray(64 * 1024)
        for (f in files) f.inputStream().use { i -> i.skip(44); while (true) { val n = i.read(buf); if (n <= 0) break; out.write(buf, 0, n) } }
    }
}
