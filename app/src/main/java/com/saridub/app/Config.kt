package com.saridub.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * API credentials may be supplied at build time (CI/local.properties) or entered in Settings
 * after installation. Runtime-entered values are stored in this app's private preferences.
 * Nothing secret is stored in source code.
 */
object Config {
    private const val PREFS = "sari_dub_api_config"
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun value(name: String, compiled: String): String {
        val context = appContext ?: return compiled.trim()
        val runtime = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(name, null)?.trim()
        return runtime?.takeIf { it.isNotEmpty() } ?: compiled.trim()
    }

    val TMDB_KEY: String get() = value("TMDB_KEY", BuildConfig.TMDB_KEY)
    val TRAKT_CLIENT_ID: String get() = value("TRAKT_CLIENT_ID", BuildConfig.TRAKT_CLIENT_ID)
    val GEMINI_KEY: String get() = value("GEMINI_KEY", BuildConfig.GEMINI_KEY)
    const val GEMINI_MODEL = "gemini-3.5-flash-lite"

    val hasTmdb: Boolean get() = TMDB_KEY.isNotBlank()
    val hasTrakt: Boolean get() = TRAKT_CLIENT_ID.isNotBlank()
    val hasGemini: Boolean get() = GEMINI_KEY.isNotBlank()

    /** Blank fields keep any previously saved or build-time key unchanged. */
    fun saveApiKeys(tmdb: String, trakt: String, gemini: String): Boolean {
        val context = appContext ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (tmdb.isNotBlank()) putString("TMDB_KEY", tmdb.trim())
            if (trakt.isNotBlank()) putString("TRAKT_CLIENT_ID", trakt.trim())
            if (gemini.isNotBlank()) putString("GEMINI_KEY", gemini.trim())
        }.apply()
        return true
    }

    fun clearSavedApiKeys(): Boolean {
        val context = appContext ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("TMDB_KEY")
            .remove("TRAKT_CLIENT_ID")
            .remove("GEMINI_KEY")
            .apply()
        return true
    }
}
