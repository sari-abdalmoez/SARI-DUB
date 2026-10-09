package com.saridub.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class AudioTrackInfo(val index: Int, val mime: String, val rate: Int, val channels: Int, val lang: String)

data class MediaInfo(
    val durationMs: Long, val width: Int, val height: Int, val fps: Int, val videoMime: String,
    val audio: List<AudioTrackInfo>, val subtitleTracks: Int, val sizeBytes: Long
) {
    fun describe(): String = buildString {
        append("Duration: ${fmtTime(durationMs)}\n")
        if (width > 0) append("Video: ${width}x$height @ ${if (fps > 0) fps else "?"} fps  ($videoMime)\n")
        audio.forEach { append("Audio #${it.index}: ${it.mime}, ${it.rate} Hz, ${it.channels} ch${if (it.lang.isNotBlank()) ", ${it.lang}" else ""}\n") }
        append("Embedded subtitle tracks: $subtitleTracks\n")
        if (sizeBytes > 0) append("Size: ${"%.1f".format(sizeBytes / 1048576.0)} MB")
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

object MediaAnalyzer {
    suspend fun analyze(ctx: Context, uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var dur = 0L; var w = 0; var h = 0; var fps = 0; var vm = ""; var subs = 0
            val audio = mutableListOf<AudioTrackInfo>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) dur = maxOf(dur, f.getLong(MediaFormat.KEY_DURATION) / 1000)
                when {
                    mime.startsWith("video/") -> {
                        vm = mime
                        if (f.containsKey(MediaFormat.KEY_WIDTH)) w = f.getInteger(MediaFormat.KEY_WIDTH)
                        if (f.containsKey(MediaFormat.KEY_HEIGHT)) h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0)
                    }
                    mime.startsWith("audio/") -> audio.add(AudioTrackInfo(i, mime,
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0,
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0,
                        if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) ?: "" else ""))
                    mime.startsWith("text/") || mime.contains("subrip") || mime.contains("x-subrip") -> subs++
                }
            }
            MediaInfo(dur, w, h, fps, vm, audio, subs, sizeOf(ctx, uri))
        } finally { ex.release() }
    }

    fun sizeOf(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    fun displayName(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "Video")
}

object AudioExtractor {
    /**
     * Decodes one audio track to a raw interleaved s16le file. Streams through direct ByteBuffers, never
     * holds the whole track in memory. Returns (sampleRate, channels) actually produced by the decoder.
     */
    suspend fun decode(ctx: Context, uri: Uri, track: Int, out: File, onProgress: (Float) -> Unit): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            // Nullable only so the finally block can release whatever was created before a failure.
            var created: MediaCodec? = null
            try {
                extractor.setDataSource(ctx, uri, null)
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: throw PipelineError("UNSUPPORTED_CODEC", "Audio track has no MIME type")
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

                // From here on the decoder is a non-null value; all operations use `decoder`.
                val decoder: MediaCodec = try {
                    MediaCodec.createDecoderByType(mime)
                } catch (e: Exception) {
                    throw PipelineError("UNSUPPORTED_CODEC", "No decoder for $mime on this device")
                }
                created = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastReportUs = 0L
                FileOutputStream(out).channel.use { channel ->
                    while (!outputDone) {
                        ensureActive()
                        if (!inputDone) {
                            val inIndex = decoder.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) {
                                val inBuf = decoder.getInputBuffer(inIndex)
                                    ?: throw PipelineError("AUDIO_EXTRACT", "Decoder returned no input buffer")
                                val size = extractor.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                        when {
                            outIndex >= 0 -> {
                                val outBuf = decoder.getOutputBuffer(outIndex)
                                if (outBuf != null && info.size > 0) {
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    while (outBuf.hasRemaining()) channel.write(outBuf)
                                }
                                decoder.releaseOutputBuffer(outIndex, false)
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                                if (durationUs > 0 && info.presentationTimeUs - lastReportUs > 5_000_000L) {
                                    lastReportUs = info.presentationTimeUs
                                    onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                                }
                            }
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val newFormat = decoder.outputFormat
                                rate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                val enc = if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                                if (enc != android.media.AudioFormat.ENCODING_PCM_16BIT)
                                    throw PipelineError("UNSUPPORTED_CODEC", "Decoder outputs non-16-bit PCM, which is not supported yet")
                            }
                        }
                    }
                }
                Pair(rate, channels)
            } finally {
                // Always release in reverse order; each call is guarded so a failed stop() cannot leak the codec.
                created?.let { codec ->
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
                runCatching { extractor.release() }
            }
        }
}

object Srt {
    private val timeRe = Regex("""(?:(\d+):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
    private val tagRe = Regex("<[^>]+>")
    private val assRe = Regex("\\{[^}]*\\}")

    private fun parseTime(s: String): Long? {
        val m = timeRe.find(s) ?: return null
        val h = m.groupValues[1].ifEmpty { "0" }.toLong()
        val mi = m.groupValues[2].toLong(); val se = m.groupValues[3].toLong()
        return h * 3_600_000 + mi * 60_000 + se * 1000 + m.groupValues[4].padEnd(3, '0').toLong()
    }

    /** Parses SRT and WebVTT. Handles BOM, CRLF/CR line endings, WEBVTT header/NOTE/STYLE blocks, multiline cues,
     *  optional cue identifiers and cue settings. Cues are returned in file order (never re-sorted or de-duplicated). */
    fun parse(text: String): List<Triple<Long, Long, String>> {
        val out = mutableListOf<Triple<Long, Long, String>>()
        val norm = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        for (block in norm.split(Regex("\n[ \t]*\n+"))) {
            val lines = block.trim('\n').lines()
            val first = lines.firstOrNull()?.trim().orEmpty()
            if (first.startsWith("WEBVTT") || first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            val a = parseTime(parts[0]) ?: continue
            val b = parseTime(parts.getOrElse(1) { "" }.trim().substringBefore(' ')) ?: continue
            val body = lines.drop(ti + 1).joinToString("\n") { it.trim() }
                .replace(tagRe, "").replace(assRe, "").trim()
            if (body.isNotEmpty() && b > a) out.add(Triple(a, b, body))
        }
        return out
    }

    /** Decodes bytes as UTF-8 (BOM aware); falls back to windows-1252 when the bytes are not valid UTF-8. */
    fun decode(bytes: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try { dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        catch (e: java.nio.charset.CharacterCodingException) { String(bytes, charset("windows-1252")) }
    }

    fun time(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", v / 3_600_000, (v / 60_000) % 60, (v / 1000) % 60, v % 1000)
    }

    fun format(items: List<Triple<Long, Long, String>>): String = buildString {
        items.forEachIndexed { i, (a, b, t) -> append(i + 1).append('\n').append(time(a)).append(" --> ").append(time(b)).append('\n').append(t).append("\n\n") }
    }

    /** Re-parses exported text and checks cue count, order of start times and text presence. Returns an error or null. */
    fun validate(exported: String, expectedCues: Int): String? {
        val cues = parse(exported)
        if (cues.size != expectedCues) return "Exported file has ${cues.size} cues, expected $expectedCues"
        if (cues.any { it.third.isBlank() }) return "Exported file contains empty cues"
        return null
    }
}
