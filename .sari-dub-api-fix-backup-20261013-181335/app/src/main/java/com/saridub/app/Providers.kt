package com.saridub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Metadata only. Nothing here is a playable video. */
data class Title(
    val key: String,
    val title: String,
    val year: Int?,
    val kind: String,           // MOVIE or SERIES
    val rating: Double,
    val poster: String?,
    val overview: String,
    val source: String,
    val altTitles: List<String> = emptyList()
)

interface TitleProvider {
    val name: String
    suspend fun search(q: String): List<Title>
}

private fun traktHeaders() = mapOf(
    "Content-Type" to "application/json",
    "trakt-api-version" to "2",
    "trakt-api-key" to Config.TRAKT_CLIENT_ID
)

private fun stripHtml(s: String) = s.replace(Regex("<[^>]*>"), "").trim()

fun tmdbToTitle(o: JSONObject, type: String): Title {
    val movie = type == "movie"
    val date = o.optString(if (movie) "release_date" else "first_air_date")
    val poster = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
        ?.let { "https://image.tmdb.org/t/p/w342$it" }
    return Title(
        key = "tmdb:$type:${o.optInt("id")}",
        title = o.optString(if (movie) "title" else "name"),
        year = date.take(4).toIntOrNull(),
        kind = if (movie) "MOVIE" else "SERIES",
        rating = o.optDouble("vote_average", 0.0),
        poster = poster,
        overview = o.optString("overview"),
        source = "TMDB",
        altTitles = listOf(o.optString(if (movie) "original_title" else "original_name"))
    )
}

fun traktToTitle(o: JSONObject, type: String): Title {
    val movie = type == "movie"
    return Title(
        key = "trakt:$type:${o.getJSONObject("ids").optInt("trakt")}",
        title = o.optString("title"),
        year = o.optInt("year", 0).takeIf { it > 0 },
        kind = if (movie) "MOVIE" else "SERIES",
        rating = o.optDouble("rating", 0.0),
        poster = null,
        overview = stripHtml(o.optString("overview")),
        source = "Trakt"
    )
}

class TmdbProvider : TitleProvider {
    override val name = "TMDB"
    override suspend fun search(q: String): List<Title> = withContext(Dispatchers.IO) {
        val out = ArrayList<Title>()
        for (lang in listOf("en-US", "ar")) {
            val url = "https://api.themoviedb.org/3/search/multi?api_key=${Config.TMDB_KEY}" +
                    "&language=$lang&include_adult=false&query=${Net.enc(q)}"
            val arr = JSONObject(Net.get(url)).optJSONArray("results") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = o.optString("media_type")
                if (type != "movie" && type != "tv") continue
                out.add(tmdbToTitle(o, type))
            }
        }
        out
    }
}

class TvMazeProvider : TitleProvider {
    override val name = "TVMaze"
    override suspend fun search(q: String): List<Title> = withContext(Dispatchers.IO) {
        val arr = JSONArray(Net.get("https://api.tvmaze.com/search/shows?q=${Net.enc(q)}"))
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i).getJSONObject("show")
            val premiered = s.optString("premiered")
            Title(
                key = "tvmaze:${s.getInt("id")}",
                title = s.optString("name"),
                year = premiered.take(4).toIntOrNull(),
                kind = "SERIES",
                rating = s.optJSONObject("rating")?.optDouble("average", 0.0) ?: 0.0,
                poster = s.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() },
                overview = stripHtml(s.optString("summary")),
                source = "TVMaze"
            )
        }
    }
}

class TraktProvider : TitleProvider {
    override val name = "Trakt"
    override suspend fun search(q: String): List<Title> = withContext(Dispatchers.IO) {
        val url = "https://api.trakt.tv/search/movie,show?query=${Net.enc(q)}&extended=full&limit=20"
        val arr = JSONArray(Net.get(url, traktHeaders()))
        (0 until arr.length()).mapNotNull { i ->
            val item = arr.getJSONObject(i)
            val type = item.optString("type")
            if (type != "movie" && type != "show") return@mapNotNull null
            traktToTitle(item.getJSONObject(type), if (type == "movie") "movie" else "show")
        }
    }
}

/** Home screen trending list. TMDB first (has posters), Trakt as failover. */
object Trending {
    suspend fun load(): List<Title> = withContext(Dispatchers.IO) {
        val tmdb = runCatching {
            val arr = JSONObject(Net.get("https://api.themoviedb.org/3/trending/movie/week?api_key=${Config.TMDB_KEY}"))
                .getJSONArray("results")
            (0 until arr.length()).map { tmdbToTitle(arr.getJSONObject(it), "movie") }
        }.getOrNull()
        if (!tmdb.isNullOrEmpty()) return@withContext tmdb
        runCatching {
            val arr = JSONArray(Net.get("https://api.trakt.tv/movies/trending?extended=full&limit=24", traktHeaders()))
            (0 until arr.length()).map { traktToTitle(arr.getJSONObject(it).getJSONObject("movie"), "movie") }
        }.getOrDefault(emptyList())
    }
}
