package com.saridub.app

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions

/** On-device language identification with the engine's real confidence score. */
object LanguageDetector {
    data class Result(val code: String, val confidence: Float)

    /** Returns null when the text is too short or the best guess is below [minConfidence] (the caller should then ask the user). */
    suspend fun detect(text: String, minConfidence: Float = 0.5f): Result? {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 12) return null
        val identifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.2f).build()
        )
        return try {
            val best = identifier.identifyPossibleLanguages(clean).awaitT()
                ?.filter { it.languageTag != "und" }
                ?.maxByOrNull { it.confidence } ?: return null
            if (best.confidence < minConfidence) null else Result(best.languageTag, best.confidence)
        } finally {
            identifier.close()
        }
    }
}
