package com.saridub.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class DubCue(val startMs: Long, val endMs: Long, val file: File?, val text: String)

object AppSettings {
    var dubEnabled by mutableStateOf(true)
    /** Language selected for video and manga translation. */
    var targetLanguage by mutableStateOf("Arabic")
}

/** Copies only the audio track for a time range without re-encoding. Returns its real start in ms. */
object VideoCutter {
    fun cut(ctx: Context, source: String, startMs: Long, endMs: Long, out: File): Long {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, android.net.Uri.parse(source), null)
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    ex.selectTrack(i)
                    map[i] = mux.addTrack(ex.getTrackFormat(i))
                }
            }
            if (map.isEmpty()) throw ApiException(ErrKind.MALFORMED, "The selected video has no readable audio track")
        ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val base = max(0L, ex.sampleTime)
            mux.start()
            val buf = ByteBuffer.allocate(4 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t > endMs * 1000) break
                val track = map[ex.sampleTrackIndex]
                if (track != null) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = max(0L, t - base)
                    info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mux.writeSampleData(track, buf, info)
                }
                ex.advance()
            }
            return base / 1000
        } finally {
            runCatching { mux.stop() }
            mux.release()
            ex.release()
        }
    }
}

/** Gives each character a stable voice. Same speaker keeps the same voice for the whole session. */
object Casting {
    private val male = listOf("ar-EG-ShakirNeural", "ar-SA-HamedNeural", "ar-MA-JamalNeural", "ar-AE-HamdanNeural")
    private val female = listOf("ar-EG-SalmaNeural", "ar-SA-ZariyahNeural", "ar-MA-MounaNeural", "ar-AE-FatimaNeural")
    private val assigned = ConcurrentHashMap<String, String>()

    fun voiceFor(speaker: String, gender: String): String = assigned.getOrPut(speaker) {
        val pool = if (gender.startsWith("f", ignoreCase = true)) female else male
        pool[assigned.size % pool.size]
    }

    fun table(): Map<String, String> = assigned.toSortedMap()
}

/** Maps a detected emotion to delivery parameters, so the dub follows the original tone. */
fun prosodyFor(emotion: String): Triple<String, String, String?> = when (emotion.lowercase()) {
    "angry" -> Triple("+12%", "+6Hz", "angry")
    "shouting" -> Triple("+18%", "+10Hz", "shouting")
    "sad" -> Triple("-10%", "-6Hz", "sad")
    "happy" -> Triple("+6%", "+4Hz", "cheerful")
    "whisper" -> Triple("-12%", "-4Hz", "whispering")
    "calm" -> Triple("-5%", "+0Hz", "calm")
    "fear" -> Triple("+8%", "+5Hz", "fearful")
    else -> Triple("+0%", "+0Hz", null)
}

/**
 * Processes one 30-second segment: cut → Gemini (transcribe, translate, emotion) → Edge-TTS per line.
 * Results are cached in memory so each segment is only processed once.
 */
object DubEngine {
    const val SEG_MS = 30_000L
    private val cache = ConcurrentHashMap<String, List<DubCue>>()

    suspend fun segment(ctx: Context, src: String, index: Int): List<DubCue> = withContext(Dispatchers.IO) {
        val key = "${src.hashCode()}|$index"
        cache[key]?.let { return@withContext it }

        val tmp = File(ctx.cacheDir, "seg_${key.hashCode()}.m4a")
        try {
            val baseMs = VideoCutter.cut(ctx, src, index * SEG_MS, (index + 1) * SEG_MS, tmp)
            val speech = Gemini.analyzeVideo(tmp.readBytes(), AppSettings.targetLanguage)
            val cues = speech.mapNotNull { c ->
                val (rate, pitch, style) = prosodyFor(c.emotion)
                val voice = Casting.voiceFor(c.speaker, c.gender)
                val bytes = runCatching {
                    withTimeout(25_000) { EdgeTts.synthesize(c.text, voice, rate, pitch, style) }
                }.getOrNull()
                val f = bytes?.let { data ->
                    File(ctx.cacheDir, "tts_${key.hashCode()}_${c.startMs}.mp3").also { it.writeBytes(data) }
                }
                // Keep the translated text cue even if voice synthesis is unavailable.
                DubCue(baseMs + c.startMs, baseMs + c.endMs, f, c.text)
            }
            cache[key] = cues
            cues
        } finally {
            tmp.delete()
        }
    }
}
