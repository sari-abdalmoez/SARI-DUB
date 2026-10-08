package com.saridub.app

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions

/** Real on-device language identification. Returns ML Kit BCP-47/ISO-style codes. */
object LanguageDetector {
    data class Result(val code: String, val confidence: Float)

    suspend fun detect(text: String): Result? {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 12) return null
        val options = LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.35f)
            .build()
        val identifier = LanguageIdentification.getClient(options)
        return try {
            val code = identifier.identifyLanguage(clean).awaitT() ?: return null
            if (code.isBlank() || code == "und") return null
            Result(code, 1f)
        } finally {
            identifier.close()
        }
    }
}
