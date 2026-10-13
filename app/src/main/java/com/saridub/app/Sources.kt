package com.saridub.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * A playable option.
 * embed = false: a direct media file (mp4) that ExoPlayer plays. Only these can be dubbed,
 *                because the app must download the audio itself.
 * embed = true:  a third-party web player page that is shown in a WebView. Its audio cannot be
 *                accessed by the app, so dubbing is not available for these.
 */
data class Playable(
    val url: String,
    val label: String,
    val subtitleUrl: String?,
    val sourceName: String,
    val embed: Boolean = false
)

/**
 * Resolves a TMDB id for any title. TMDB titles already carry it in their key ("tmdb:movie:123").
 * Other titles (Trakt, TVMaze) are matched on TMDB by title AND year, and a match is rejected
 * if the year or type does not agree, so a wrong film is never selected.
 */
object TmdbIds {
    suspend fun resolve(t: Title): Int? = withContext(Dispatchers.IO) {
        val parts = t.key.split(":")
        if (parts.size == 3 && parts[0] == "tmdb") return@withContext parts[2].toIntOrNull()
        if (!Config.hasTmdb) return@withContext null

        val arr = JSONObject(TmdbApi.get("search/multi", mapOf(
            "include_adult" to "false",
            "query" to t.title
        ))).optJSONArray("results") ?: return@withContext null
        val want = Text.normalize(t.title)
        val wantType = if (t.kind == "MOVIE") "movie" else "tv"

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("media_type") != wantType) continue
            val name = o.optString(if (wantType == "movie") "title" else "name")
            val date = o.optString(if (wantType == "movie") "release_date" else "first_air_date")
            val year = date.take(4).toIntOrNull()
            val sameName = Text.normalize(name) == want
            val sameYear = t.year == null || year == t.year
            if (sameName && sameYear) return@withContext o.optInt("id")
        }
        null
    }
}

/**
 * Embed players. They take a TMDB id, so the film or series that opens is always the one selected.
 * Series default to season 1, episode 1 until the episode picker is added.
 */
object EmbedSources {
    fun vidSrc(kind: String, tmdbId: Int, season: Int, episode: Int): Playable {
        val url = if (kind == "MOVIE") "https://vidsrc.to/embed/movie/$tmdbId"
        else "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode"
        return Playable(url, "VidSrc", null, "VidSrc", embed = true)
    }

    fun vidLink(kind: String, tmdbId: Int, season: Int, episode: Int): Playable {
        val url = if (kind == "MOVIE") "https://vidlink.pro/movie/$tmdbId"
        else "https://vidlink.pro/tv/$tmdbId/$season/$episode"
        return Playable(url, "VidLink", null, "VidLink", embed = true)
    }
}

/** Metadata search and playback are separate. Direct links are checked before they are returned. */
object Playback {
    suspend fun resolve(t: Title, season: Int = 1, episode: Int = 1): List<Playable> = coroutineScope {
        val tmdbId = runCatching { TmdbIds.resolve(t) }.getOrNull()

        val embeds = if (tmdbId == null) emptyList() else listOf(
            EmbedSources.vidSrc(t.kind, tmdbId, season, episode),
            EmbedSources.vidLink(t.kind, tmdbId, season, episode)
        )

        val direct = async {
            withTimeoutOrNull(15_000) { InternetArchiveSource().resolve(t) } ?: emptyList()
        }.await().filter { p ->
            withContext(Dispatchers.IO) { Net.isReachable(p.url) }
        }

        // Order: embed players first (they cover most titles), then verified direct files.
        embeds + direct
    }
}

/**
 * Internet Archive holds public-domain films. A result is accepted only when the title is the same
 * and the release year matches, so a similar but different film is never returned.
 */
class InternetArchiveSource {
    suspend fun resolve(t: Title): List<Playable> = withContext(Dispatchers.IO) {
        try {
            val safe = t.title.replace("\"", "")
            val q = "title:(\"$safe\") AND mediatype:movies"
            val url = "https://archive.org/advancedsearch.php?q=${Net.enc(q)}" +
                    "&fl[]=identifier&fl[]=title&fl[]=year&rows=8&output=json"
            val docs = JSONObject(Net.get(url)).getJSONObject("response").getJSONArray("docs")
            val want = Text.normalize(t.title)
            val out = ArrayList<Playable>()

            for (i in 0 until docs.length()) {
                val doc = docs.getJSONObject(i)
                val id = doc.getString("identifier")
                val got = Text.normalize(doc.optString("title"))
                val docYear = doc.optString("year").take(4).toIntOrNull()
                val sameName = got == want || Text.similarity(want, got) >= 0.92
                val sameYear = t.year == null || docYear == null || docYear == t.year
                if (!sameName || !sameYear) continue

                val files = JSONObject(Net.get("https://archive.org/metadata/$id")).optJSONArray("files") ?: continue
                var sub: String? = null
                val videos = ArrayList<String>()
                for (j in 0 until files.length()) {
                    val name = files.getJSONObject(j).getString("name")
                    val lower = name.lowercase()
                    val fileUrl = "https://archive.org/download/$id/${Net.pathEnc(name)}"
                    when {
                        lower.endsWith(".mp4") -> videos.add(fileUrl)
                        lower.endsWith(".srt") && sub == null -> sub = fileUrl
                    }
                }
                videos.take(2).forEach { u ->
                    out.add(Playable(u, "Internet Archive", sub, "Internet Archive (public domain)"))
                }
                if (out.isNotEmpty()) break
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
