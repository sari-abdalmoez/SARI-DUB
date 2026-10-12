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

/** TMDB accepts either a v3 API key in the query or a v4 Read Access Token as Bearer. */
private fun tmdbAuth(): Pair<String, Map<String, String>> {
    val credential = Config.TMDB_KEY.trim()
    val bearerToken = credential.startsWith("eyJ") && credential.count { it == '.' } >= 2
    return if (bearerToken) "" to mapOf("Authorization" to "Bearer $credential")
    else "api_key=${Net.enc(credential)}&" to emptyMap()
}


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
        if (Config.TMDB_KEY.isBlank()) throw ApiException(ErrKind.MISSING_KEY, "TMDB_KEY is not configured; add it in Settings")
        if (q.isBlank()) return@withContext emptyList()
        // Match the likely language first. If it has no matches, retry in the other language.
        val firstLanguage = if (q.any { it in '\u0600'..'\u06FF' }) "ar" else "en-US"
        suspend fun lookup(language: String): List<Title> {
            val (authQuery, headers) = tmdbAuth()
            val url = "https://api.themoviedb.org/3/search/multi?${authQuery}language=$language&include_adult=false&query=${Net.enc(q)}"
            val arr = JSONObject(Net.get(url, headers, timeout = 6500)).optJSONArray("results") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = item.optString("media_type")
                if (type != "movie" && type != "tv") return@mapNotNull null
                tmdbToTitle(item, type)
            }
        }
        val primary = lookup(firstLanguage)
        if (primary.isNotEmpty()) primary else lookup(if (firstLanguage == "ar") "en-US" else "ar")
    }
}

/** No-key metadata fallback for movies and series. It never returns a playable media URL. */
class CinemetaProvider : TitleProvider {
    override val name = "Cinemeta"

    override suspend fun search(q: String): List<Title> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        val encoded = Net.enc(q).replace("+", "%20")
        val results = ArrayList<Title>()
        val errors = ArrayList<Exception>()
        for ((catalog, kind) in listOf("movie" to "MOVIE", "series" to "SERIES")) {
            try {
                val endpoint = "https://v3-cinemeta.strem.io/catalog/$catalog/top/search=$encoded.json"
                val metas = JSONObject(Net.get(endpoint, timeout = 6500)).optJSONArray("metas") ?: continue
                for (i in 0 until metas.length()) {
                    val item = metas.optJSONObject(i) ?: continue
                    val title = item.optString("name").trim()
                    if (title.isBlank()) continue
                    val release = item.optString("releaseInfo", item.optString("year"))
                    val year = Regex("(?:19|20)\\d{2}").find(release)?.value?.toIntOrNull()
                    val poster = item.optString("poster").takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    results += Title(
                        key = "cinemeta:$catalog:${item.optString("id")}",
                        title = title,
                        year = year,
                        kind = kind,
                        rating = item.optString("imdbRating").toDoubleOrNull() ?: 0.0,
                        poster = poster,
                        overview = item.optString("description").replace(Regex("<[^>]*>"), " ").trim(),
                        source = "Cinemeta"
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += e
            }
        }
        if (results.isEmpty() && errors.size == 2) throw errors.first()
        results
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
        if (Config.TRAKT_CLIENT_ID.isBlank()) throw ApiException(ErrKind.MISSING_KEY, "TRAKT_CLIENT_ID is missing from the release configuration")
        if (q.isBlank()) return@withContext emptyList()
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
            if (Config.TMDB_KEY.isBlank()) throw ApiException(ErrKind.MISSING_KEY, "TMDB_KEY is missing")
            val (authQuery, headers) = tmdbAuth()
            val arr = JSONObject(Net.get("https://api.themoviedb.org/3/trending/movie/week?${authQuery}language=en-US", headers))
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
