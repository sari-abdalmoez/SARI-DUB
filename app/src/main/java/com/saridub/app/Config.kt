package com.saridub.app

/** All keys live here. Move them to local.properties + BuildConfig before publishing. */
object Config {
    const val TMDB_KEY = "8616f4a1b6066749e70c46ec30433290"
    const val TRAKT_CLIENT_ID = "_rYR4wL-bD86B0Vq4XFu633zPKQH-RFid2FarJmPxUo"
    val GEMINI_KEY: String get() = BuildConfig.GEMINI_KEY
    const val GEMINI_MODEL = "gemini-2.5-flash-lite"
}
