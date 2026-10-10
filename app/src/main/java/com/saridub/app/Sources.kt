package com.saridub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** A real, playable URL from a licensed/public-domain source, plus an optional subtitle file. */
data class Playable(val url: String, val label: String, val subtitleUrl: String?, val sourceName: String)

interface PlaybackSource {
    val name: String
    suspend fun resolve(t: Title): List<Playable>
}

/** Keeps metadata search separate from playback. Returns only sources that really exist. */
object Playback {
    private val sources: List<PlaybackSource> = listOf(InternetArchiveSource())

    suspend fun resolve(t: Title): List<Playable> = coroutineScope {
        sources.map { s ->
            async {
                try {
                    withTimeoutOrNull(15_000) { s.resolve(t) } ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }
}

/** Internet Archive holds public-domain films. Only matches with a very similar title are used. */
class InternetArchiveSource : PlaybackSource {
    override val name = "Internet Archive"

    override suspend fun resolve(t: Title): List<Playable> = withContext(Dispatchers.IO) {
        val safe = t.title.replace("\"", "")
        val q = "title:(\"$safe\") AND mediatype:movies"
        val url = "https://archive.org/advancedsearch.php?q=${Net.enc(q)}" +
                "&fl[]=identifier&fl[]=title&rows=6&output=json"
        val docs = JSONObject(Net.get(url)).getJSONObject("response").getJSONArray("docs")
        val want = Text.normalize(t.title)
        val out = ArrayList<Playable>()

        for (i in 0 until docs.length()) {
            val doc = docs.getJSONObject(i)
            val id = doc.getString("identifier")
            if (Text.similarity(want, Text.normalize(doc.optString("title"))) < 0.7) continue

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
    }
}
