package com.saridub.app

import android.content.Context

/**
 * API keys come from two places, in this order:
 *  1. Keys entered in the app's Settings screen, stored in the app's private SharedPreferences.
 *  2. Keys compiled in at build time from local.properties or CI secrets (BuildConfig).
 * No secret is written in source code.
 */
object Config {
    private const val PREFS = "sari_dub_api_config"
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun runtimeValue(name: String): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(name, null)?.trim()?.takeIf { it.isNotEmpty() }

    val TMDB_KEY: String get() = runtimeValue("TMDB_KEY") ?: BuildConfig.TMDB_KEY.trim()
    val TRAKT_CLIENT_ID: String get() = runtimeValue("TRAKT_CLIENT_ID") ?: BuildConfig.TRAKT_CLIENT_ID.trim()
    val GEMINI_KEY: String get() = runtimeValue("GEMINI_KEY") ?: BuildConfig.GEMINI_KEY.trim()

    const val GEMINI_MODEL = "gemini-2.5-flash-lite"

    val hasTmdb: Boolean get() = TMDB_KEY.isNotBlank()
    val hasTrakt: Boolean get() = TRAKT_CLIENT_ID.isNotBlank()
    val hasGemini: Boolean get() = GEMINI_KEY.isNotBlank()

    /** Blank fields keep the previously saved value. Returns false if the app context is missing. */
    fun saveApiKeys(tmdb: String, trakt: String, gemini: String): Boolean {
        val ctx = appContext ?: return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (tmdb.isNotBlank()) putString("TMDB_KEY", tmdb.trim())
            if (trakt.isNotBlank()) putString("TRAKT_CLIENT_ID", trakt.trim())
            if (gemini.isNotBlank()) putString("GEMINI_KEY", gemini.trim())
        }.apply()
        return true
    }

    fun clearSavedApiKeys(): Boolean {
        val ctx = appContext ?: return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        return true
    }
}
