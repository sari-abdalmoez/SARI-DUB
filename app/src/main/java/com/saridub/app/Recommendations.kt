package com.saridub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Related titles from TMDB, only for titles that came from TMDB. Nothing is invented. */
object Recommendations {
    suspend fun load(t: Title): List<Title> = withContext(Dispatchers.IO) {
        val m = Regex("tmdb:(movie|tv):(\\d+)").find(t.key) ?: return@withContext emptyList()
        val (type, id) = m.destructured
        val arr = JSONObject(TmdbApi.get("$type/$id/recommendations", mapOf("language" to "en-US")))
            .optJSONArray("results") ?: return@withContext emptyList()
        (0 until minOf(arr.length(), 15)).map { tmdbToTitle(arr.getJSONObject(it), type) }
    }
}
