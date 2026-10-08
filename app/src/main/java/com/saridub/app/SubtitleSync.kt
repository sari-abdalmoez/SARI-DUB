package com.saridub.app

import java.io.File

/** Aligns a downloaded subtitle file to the real audio using VAD speech bins and the native lag search. */
object SubtitleSync {
    data class Result(val offsetMs: Long, val precision: Float, val density: Float, val ok: Boolean)

    private const val BIN_MS = 20
    private const val MAX_OFFSET_MS = 120_000    // search +-2 min (covers intro/ad offsets)

    /**
     * speechBins: 1 per 20 ms from VAD intervals (in ms). subBins: same for subtitle cues.
     * Returns the offset to add to subtitle times. ok=false means the match is too weak to trust.
     */
    fun align(vadSeconds: DoubleArray, cues: List<Triple<Long, Long, String>>, durationMs: Long): Result {
        val n = (durationMs / BIN_MS).toInt() + 1
        val speech = ByteArray(n)
        for (i in 0 until vadSeconds.size / 2) {
            val a = (vadSeconds[2 * i] * 1000 / BIN_MS).toInt().coerceIn(0, n - 1)
            val b = (vadSeconds[2 * i + 1] * 1000 / BIN_MS).toInt().coerceIn(0, n - 1)
            for (k in a..b) speech[k] = 1
        }
        val sub = ByteArray(n)
        for ((s, e, _) in cues) {
            val a = (s / BIN_MS).toInt().coerceIn(0, n - 1); val b = (e / BIN_MS).toInt().coerceIn(0, n - 1)
            for (k in a..b) sub[k] = 1
        }
        val maxLag = MAX_OFFSET_MS / BIN_MS
        val r = Native.bestLag(speech, sub, 0, n, -maxLag, maxLag) ?: return Result(0, 0f, 0f, false)
        val lagBins = r[0].toInt()
        val ok = r[1] >= 0.6f && r[1] > r[2] + 0.2f   // matches speech much better than chance
        return Result(lagBins.toLong() * BIN_MS, r[1], r[2], ok)
    }

    /** Parse, shift and write back a corrected .srt file. */
    fun shiftSrt(srt: String, offsetMs: Long): String {
        val items = Srt.parse(srt).map { (s, e, t) -> Triple((s + offsetMs).coerceAtLeast(0), (e + offsetMs).coerceAtLeast(0), t) }
        return Srt.format(items)
    }
}

/** Aligns subtitle files supplied by the user (SRT) to the real audio. No external subtitle service is used. */
object SubtitleFetcher {
    fun alignUserSrt(srtText: String, cacheDir: File, vadSeconds: DoubleArray, durationMs: Long): Pair<File, SubtitleSync.Result>? {
        val cues = Srt.parse(srtText)
        if (cues.isEmpty()) return null
        val r = SubtitleSync.align(vadSeconds, cues, durationMs)
        if (!r.ok) return null
        val out = File(cacheDir, "sub_aligned.srt")
        out.writeText(SubtitleSync.shiftSrt(srtText, r.offsetMs))
        return out to r
    }
}
