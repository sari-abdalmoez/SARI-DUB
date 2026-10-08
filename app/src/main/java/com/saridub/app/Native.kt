package com.saridub.app

/** Thin JNI surface: paths/ids/numbers in, status or tiny arrays out. No media buffers cross JNI. */
object Native {
    init { System.loadLibrary("saridub") }

    external fun setCancel(cancel: Boolean)
    external fun resampleFile(inPath: String, inRate: Int, inCh: Int, outPath: String, outRate: Int): Long
    external fun vad(pcmPath: String, rate: Int, minSpeechMs: Int, minSilenceMs: Int): DoubleArray?
    external fun voiceProfile(pcmPath: String, rate: Int, startSec: Double, endSec: Double): FloatArray?
    external fun stretchWav(inWav: String, outWav: String, rate: Int, targetMs: Int, minRatio: Float, maxRatio: Float): Int
    external fun mixChunk(
        origPcm: String, rate: Int, startSec: Double, endSec: Double,
        segStartSec: DoubleArray, segWavs: Array<String>, outWav: String, duckGain: Float
    ): Int
    external fun wavDurationMs(path: String): Int
    external fun extractWav(pcmPath: String, rate: Int, startSec: Double, endSec: Double, outRate: Int, outWav: String): Int
    external fun bestLag(speech: ByteArray, sub: ByteArray, from: Int, to: Int, minLag: Int, maxLag: Int): FloatArray?

    const val CANCELLED = -2
}
