package com.saridub.app

/** A playable URL supplied by an explicitly configured/approved playback provider. */
data class Playable(val url: String, val label: String, val subtitleUrl: String?, val sourceName: String)

interface PlaybackSource {
    val name: String
    suspend fun resolve(t: Title): List<Playable>
}

/**
 * External movie playback is disabled in this build.
 * TMDB, Trakt, TVMaze and Cinemeta provide metadata/search, not movie video files.
 * The local-file player and translation workflow are separate and remain available.
 */
object Playback {
    @Suppress("UNUSED_PARAMETER")
    suspend fun resolve(t: Title): List<Playable> = emptyList()
}