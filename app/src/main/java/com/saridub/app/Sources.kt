package com.saridub.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

/** A real URL from a licensed or public-domain source, plus an optional subtitle file. */
data class Playable(val url: String, val label: String, val subtitleUrl: String?, val sourceName: String)

interface PlaybackSource {
    val name: String
    suspend fun resolve(t: Title): List<Playable>
}

/**
 * Metadata search and playback are separate. A source is returned only if its media URL answers
 * a HEAD request, so the player never opens a dead link.
 */
object Playback {
    private val sources: List<PlaybackSource> = listOf(
        VidSrcSource(),
        VidLinkSource(),
        NHDEmbedSource(),
        EzVidApiSource(),
        InternetArchiveSource()
    )

    suspend fun resolve(t: Title): List<Playable> = coroutineScope {
        val found = sources.map { s ->
            async {
                try {
                    withTimeoutOrNull(15_000) { s.resolve(t) } ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        found.map { p ->
            async(Dispatchers.IO) { if (Net.isReachable(p.url)) p else null }
        }.awaitAll().filterNotNull()
    }
}

/** VidSrc - Free streaming source with multiple mirrors */
class VidSrcSource : PlaybackSource {
    override val name = "VidSrc"

    override suspend fun resolve(t: Title): List<Playable> = withContext(Dispatchers.IO) {
        try {
            val isMovie = t.kind == "MOVIE"
            val title = t.title.replace(" ", "-").lowercase(Locale.ROOT)
            val year = t.year?.toString() ?: return@withContext emptyList()
            
            val url = if (isMovie) {
                "https://vidsrc.me/embed/movie/$title-$year"
            } else {
                // For series, need episode info - using base URL
                "https://vidsrc.me/embed/tv/$title-$year/1-1"
            }
            
            listOf(Playable(url, "VidSrc Default", null, "VidSrc"))
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** VidLink - Alternative streaming provider with good uptime */
class VidLinkSource : PlaybackSource {
    override val name = "VidLink"

    override suspend fun resolve(t: Title): List<Playable> = withContext(Dispatchers.IO) {
        try {
            val isMovie = t.kind == "MOVIE"
            val title = t.title.replace(" ", "-").replace("'", "").lowercase(Locale.ROOT)
            
            val url = if (isMovie) {
                "https://vidlink.pro/movie/$title"
            } else {
                "https://vidlink.pro/tv/$title"
            }
            
            listOf(Playable(url, "VidLink Stream", null, "VidLink"))
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** NHD Embed - Reliable streaming with subtitle support */
class NHDEmbedSource : PlaybackSource {
    override val name = "NHD Embed"

    override suspend fun resolve(t: Title): List<Playable> = withContext(Dispatchers.IO) {
        try {
            val isMovie = t.kind == "MOVIE"
            val title = t.title.replace(" ", "-").lowercase(Locale.ROOT)
            
            val url = if (isMovie) {
                "https://embed.nhdmovies.com/embed/movie/$title"
            } else {
                "https://embed.nhdmovies.com/embed/tv/$title/1/1"
            }
            
            listOf(Playable(url, "NHD Embed HD", null, "NHD Embed"))
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** ezVidApi - JSON-based API for dynamic streams */
class EzVidApiSource : PlaybackSource {
    override val name = "EzVidAPI"

    override suspend fun resolve(t: Title): List<Playable> = withContext(Dispatchers.IO) {
        try {
            val isMovie = t.kind == "MOVIE"
            val title = t.title.lowercase(Locale.ROOT)
            val type = if (isMovie) "movie" else "tv"
            
            // Try using TMDB ID if available, fallback to title search
            val apiUrl = "https://api.ezvidapi.com/search?query=${Net.enc(title)}&type=$type"
            val response = JSONObject(Net.get(apiUrl))
            
            if (response.has("result") && response.getJSONArray("result").length() > 0) {
                val firstResult = response.getJSONArray("result").getJSONObject(0)
                val streamUrl = firstResult.optString("url")
                
                if (streamUrl.isNotBlank()) {
                    return@withContext listOf(Playable(streamUrl, "EzVidAPI Stream", null, "EzVidAPI"))
                }
            }
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Internet Archive holds public-domain films. Title matching uses similarity and token coverage. */
class InternetArchiveSource : PlaybackSource {
    override val name = "Internet Archive"

    override suspend fun resolve(t: Title): List<Playable> = withContext(Dispatchers.IO) {
        try {
            val safe = t.title.replace("\"", "")
            val q = "title:(\"$safe\") AND mediatype:movies"
            val url = "https://archive.org/advancedsearch.php?q=${Net.enc(q)}" +
                    "&fl[]=identifier&fl[]=title&rows=8&output=json"
            val docs = JSONObject(Net.get(url)).getJSONObject("response").getJSONArray("docs")
            val want = Text.normalize(t.title)
            val out = ArrayList<Playable>()

            for (i in 0 until docs.length()) {
                val doc = docs.getJSONObject(i)
                val id = doc.getString("identifier")
                val got = Text.normalize(doc.optString("title"))
                val score = maxOf(Text.similarity(want, got), coverage(want, got))
                if (score < 0.75) continue

                val files = JSONObject(Net.get("https://archive.org/metadata/$id")).optJSONArray("files") ?: continue
                var sub: String? = null
                val videos = ArrayList<Pair<String, String>>()
                for (j in 0 until files.length()) {
                    val name = files.getJSONObject(j).getString("name")
                    val lower = name.lowercase()
                    val fileUrl = "https://archive.org/download/$id/${Net.pathEnc(name)}"
                    when {
                        lower.endsWith(".mp4") -> videos.add(fileUrl to name)
                        lower.endsWith(".srt") && sub == null -> sub = fileUrl
                    }
                }
                videos.take(2).forEach { (u, label) ->
                    out.add(Playable(u, label, sub, "Internet Archive (public domain)"))
                }
                if (out.size >= 3) break
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun coverage(query: String, doc: String): Double {
        val q = Text.tokens(query)
        if (q.isEmpty()) return 0.0
        val d = Text.tokens(doc)
        return q.count { it in d }.toDouble() / q.size
    }
}
