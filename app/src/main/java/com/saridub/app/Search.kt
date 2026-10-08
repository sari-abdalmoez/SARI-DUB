package com.saridub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Metadata-level result. `kind` is MOVIE, SERIES, EPISODE or DOCUMENTARY.
 * `runtimeMin` is 0 when unknown.
 */
data class Title(
    val key: String, val title: String, val year: Int, val kind: String, val runtimeMin: Int,
    val rating: Float, val genres: List<String>, val overview: String, val posterUrl: String?,
    val source: String, val watchUrl: String?, val languages: List<String> = emptyList(),
    val season: Int = 0, val episode: Int = 0
)

/** Provider contract. Implement only legal/licensed sources. Each provider reports its own failures. */
interface TitleProvider {
    val name: String
    suspend fun search(query: String, year: Int?): List<Title>
}

/** Small TTL cache so repeated searches and poster lookups do not hit the network again. */
object SearchCache {
    private data class Entry(val at: Long, val value: Any)
    private val map = ConcurrentHashMap<String, Entry>()
    private const val TTL = 15 * 60_000L
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = map[key]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.value as? T
    fun put(key: String, v: Any) { if (map.size > 300) map.clear(); map[key] = Entry(System.currentTimeMillis(), v) }
}

object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 12_000): String {
        var last: Exception? = null
        for (attempt in 0..2) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", "SARI-DUB/0.2 (+local)")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                try {
                    val code = c.responseCode
                    if (code == 429 || code >= 500) throw java.io.IOException("HTTP $code (retry)")
                    if (code !in 200..299) throw java.io.IOException("HTTP $code")
                    return c.inputStream.bufferedReader().use { it.readText() }
                } finally { c.disconnect() }
            } catch (e: Exception) {
                last = e
                if (e is java.io.IOException && e.message?.contains("HTTP 4") == true && e.message?.contains("429") != true) break
                Thread.sleep(400L shl attempt)
            }
        }
        throw last ?: java.io.IOException("request failed")
    }
    fun post(url: String, body: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 15_000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
}

/** Query understanding: multilingual normalisation, fuzzy correction, year/season extraction. */
object Query {
    data class Parsed(val text: String, val year: Int?, val season: Int?, val episode: Int?, val wantSeries: Boolean)

    private val seasonEp = Regex("""\bs(\d{1,2})\s*e(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val yearRe = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val stop = setOf("movie", "film", "full", "watch", "online", "hd", "download", "free", "the", "a", "an")

    fun parse(raw: String): Parsed {
        val s = raw.trim()
        val se = seasonEp.find(s)
        val year = yearRe.find(s)?.value?.toInt()
        val cleaned = s.replace(seasonEp, " ").replace(yearRe, " ")
        val wantSeries = se != null || Regex("""\b(series|season|episode|show|serie|temporada)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)
        val words = normalize(cleaned).split(' ').filter { it.isNotBlank() }
        val kept = if (words.size > 1) words.filter { it !in stop }.ifEmpty { words } else words
        return Parsed(kept.joinToString(" "), year, se?.groupValues?.get(1)?.toInt(), se?.groupValues?.get(2)?.toInt(), wantSeries)
    }

    /** Lowercase, strip diacritics and punctuation, unify Arabic letter variants (works for Latin/Arabic/CJK). */
    fun normalize(s: String): String {
        val n = java.text.Normalizer.normalize(s.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه').replace('ـ', ' ')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return n.replace(Regex("\\s+"), " ")
    }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    /** Similarity 0..1 using token-set fuzzy matching, so word order and one missing word still match. */
    fun similarity(q: String, t: String): Double {
        val a = normalize(q); val b = normalize(t)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val ta = a.split(' '); val tb = b.split(' ')
        val tokenScores = ta.map { w -> tb.maxOf { x -> 1.0 - lev(w, x).toDouble() / maxOf(w.length, x.length) } }
        val tokenAvg = tokenScores.average()
        val contain = if (b.contains(a) || a.contains(b)) 0.9 else 0.0
        val whole = 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
        return maxOf(whole, tokenAvg * 0.9 + 0.1 * (if (ta.size <= tb.size) ta.size.toDouble() / tb.size else tb.size.toDouble() / ta.size), contain)
    }
}

/** Public-domain / Creative Commons movies via the Internet Archive advancedsearch API. */
class ArchiveProvider : TitleProvider {
    override val name = "Internet Archive"
    override suspend fun search(query: String, year: Int?): List<Title> = withContext(Dispatchers.IO) {
        val words = query.split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return@withContext emptyList()
        val fuzzy = words.joinToString(" ") { if (it.length > 4) "$it~" else it }
        val q = "title:($fuzzy) AND mediatype:movies" + (year?.let { " AND year:$it" } ?: "")
        val url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, "UTF-8") +
            "&fl[]=identifier&fl[]=title&fl[]=year&fl[]=description&fl[]=runtime&fl[]=language&rows=30&output=json"
        val docs = JSONObject(Http.get(url)).getJSONObject("response").getJSONArray("docs")
        (0 until docs.length()).map { docs.getJSONObject(it) }.map { d ->
            val id = d.getString("identifier")
            Title(
                key = "ia:$id", title = d.optString("title", id), year = d.optString("year").toIntOrNull() ?: 0,
                kind = "MOVIE", runtimeMin = parseRuntime(d.optString("runtime")), rating = 0f, genres = emptyList(),
                overview = d.optString("description").take(600), posterUrl = "https://archive.org/services/img/$id",
                source = name, watchUrl = null, languages = listOf(d.optString("language"))
            )
        }
    }

    private fun parseRuntime(s: String): Int {
        Regex("""(\d+)\s*:\s*(\d+)""").find(s)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        return s.toIntOrNull()?.let { if (it > 1000) it / 60 else it } ?: 0
    }

    suspend fun resolveFile(id: String): String? = withContext(Dispatchers.IO) {
        val files = JSONObject(Http.get("https://archive.org/metadata/$id")).optJSONArray("files") ?: return@withContext null
        (0 until files.length()).map { files.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".mp4", true) && it.optString("source") != "metadata" }
            .maxByOrNull { it.optString("size", "0").toLongOrNull() ?: 0L }
            ?.let { "https://archive.org/download/$id/" + URLEncoder.encode(it.getString("name"), "UTF-8").replace("+", "%20") }
    }
}

/** Fan-out search across providers: concurrent, time-boxed, failure-isolated, de-duplicated and ranked. */
class SearchHub(private val providers: List<TitleProvider>) {
    data class Outcome(val titles: List<Title>, val intent: Query.Parsed, val errors: List<String>)

    suspend fun search(raw: String): Outcome = coroutineScope {
        val p = Query.parse(raw)
        val cacheKey = "q:${p.text}|${p.year}|${p.season}|${p.episode}"
        SearchCache.get<List<Title>>(cacheKey)?.let { return@coroutineScope Outcome(it, p, emptyList()) }
        val errors = ConcurrentHashMap.newKeySet<String>()
        val results = providers.map { prov ->
            async {
                runCatching { kotlinx.coroutines.withTimeout(20_000) { prov.search(p.text, p.year) } }
                    .getOrElse { errors.add("${prov.name}: ${it.message ?: "failed"}"); emptyList() }
            }
        }.flatMap { it.await() }

        val merged = results
            .filter { t -> passesKindRules(t, p) }
            .groupBy { Query.normalize(it.title) + "|" + it.year + "|" + it.kind }
            .map { (_, g) -> g.maxBy { it.overview.length + (if (it.posterUrl != null) 1 else 0) } }
            .sortedWith(compareByDescending<Title> { Query.similarity(p.text, it.title) }
                .thenByDescending { if (p.year != null && it.year == p.year) 1 else 0 }
                .thenByDescending { it.rating })
        SearchCache.put(cacheKey, merged)
        Outcome(merged, p, errors.toList())
    }

    /** Movies must exceed 30 minutes; series/episodes are never hidden by the duration rule. */
    fun passesKindRules(t: Title, p: Query.Parsed): Boolean {
        if (Query.similarity(p.text, t.title) < 0.45) return false
        if (t.kind == "MOVIE" && t.runtimeMin in 1..30) return false
        if (t.kind == "EPISODE" && p.season != null && t.season != p.season) return false
        return true
    }
}

/** Bridge kept for callers that expect a single hub factory. */

object SearchBridge {
    fun hub() = SearchHub(listOf(ArchiveProvider()))
}
