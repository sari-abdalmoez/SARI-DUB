package com.saridub.app

/** Values come from local.properties through BuildConfig. Nothing secret is stored in source. */
object Config {
    val TMDB_KEY: String get() = BuildConfig.TMDB_KEY
    val TRAKT_CLIENT_ID: String get() = BuildConfig.TRAKT_CLIENT_ID
    val GEMINI_KEY: String get() = BuildConfig.GEMINI_KEY
    const val GEMINI_MODEL = "gemini-3.5-flash-lite"
    val hasTmdb: Boolean get() = TMDB_KEY.isNotBlank()
    val hasTrakt: Boolean get() = TRAKT_CLIENT_ID.isNotBlank()
    val hasGemini: Boolean get() = GEMINI_KEY.isNotBlank()
}
