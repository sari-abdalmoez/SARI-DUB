package com.saridub.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    val season: Int = 0, val episode: Int = 0,
    /** True only when a media file and its duration were verified from provider metadata. */
    val playable: Boolean = false
)

/** Provider contract. Implement only legal/licensed sources. Each provider reports its own failures. */
interface TitleProvider {
    val name: String
    suspend fun search(query: String, year: Int?, page: Int = 1): List<Title>
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

/** Minimum runtime for a MOVIE result: strictly more than this many minutes. Episodes are exempt. */
const val MIN_MOVIE_MINUTES = 35

data class Episode(val season: Int, val episode: Int, val title: String, val url: String, val runtimeMin: Int)

/** Season/episode parsing and numeric ordering (never alphabetical: E10 must come after E2). */
object Episodes {
    private val patterns = listOf(
        Regex("""s(\d{1,2})\s*[ ._-]?\s*e(\d{1,3})""", RegexOption.IGNORE_CASE),
        Regex("""(?<!\d)(\d{1,2})x(\d{2,3})(?!\d)""", RegexOption.IGNORE_CASE),
        Regex("""season\s*(\d{1,2}).{0,20}?episode\s*(\d{1,3})""", RegexOption.IGNORE_CASE)
    )

    /** Returns (season, episode) only when both numbers are present in the name. */
    fun parse(name: String): Pair<Int, Int>? {
        for (re in patterns) re.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return null
    }

    fun sort(list: List<Episode>): List<Episode> = list.sortedWith(compareBy<Episode>({ it.season }, { it.episode }, { it.title }))

    fun label(e: Episode): String = "S%02d E%02d".format(e.season, e.episode)
}

/** Ranking and de-duplication helpers, kept pure so they can be unit tested. */
object Ranking {
    private val noise = Regex("""\b(full movie|full film|hd|4k|1080p|720p|480p|restored|remastered|public domain|free|official)\b""")

    fun canonicalTitle(t: String): String =
        Query.normalize(t).replace(noise, " ").replace(Regex("""\b(19|20)\d{2}\b"""), " ").replace(Regex("\\s+"), " ").trim()

    fun isJunkTitle(t: String): Boolean =
        Regex("""\b(trailer|teaser|promo|clip|sample|behind the scenes|bloopers?|soundtrack|audiobook)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)

    fun score(t: Title, p: Query.Parsed): Double {
        var s = Query.similarity(p.text, t.title)
        if (Query.normalize(p.text) == Query.normalize(t.title)) s += 0.5
        if (p.year != null && t.year == p.year) s += 0.25
        if (p.wantSeries && (t.kind == "SERIES" || t.kind == "EPISODE")) s += 0.2
        if (!p.wantSeries && t.kind == "MOVIE") s += 0.05
        if (t.playable) s += 0.1
        if (t.overview.isNotBlank()) s += 0.02
        if (t.posterUrl != null) s += 0.01
        return s
    }
}

/** Public-domain / Creative Commons video via the Internet Archive. Every result is verified from item metadata. */
class ArchiveProvider : TitleProvider {
    override val name = "Internet Archive"
    private val playableExt = listOf(".mp4", ".m4v", ".webm", ".mkv")
    private val maxVerify = 12

    data class Resolved(val url: String, val runtimeMin: Int, val episodes: List<Episode>)

    override suspend fun search(query: String, year: Int?, page: Int): List<Title> = withContext(Dispatchers.IO) {
        val words = query.split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return@withContext emptyList()
        val fuzzy = words.joinToString(" ") { if (it.length > 4) "$it~" else it }
        val q = "title:($fuzzy) AND mediatype:movies" + (year?.let { " AND year:$it" } ?: "")
        val url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, "UTF-8") +
            "&fl[]=identifier&fl[]=title&fl[]=year&fl[]=description&fl[]=language&rows=30&page=${page.coerceAtLeast(1)}&output=json"
        val docs = JSONObject(Http.get(url)).optJSONObject("response")?.optJSONArray("docs")
            ?: throw java.io.IOException("Unexpected response from Internet Archive")
        val base = (0 until docs.length()).mapNotNull { i ->
            val d = docs.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optString("identifier").ifBlank { return@mapNotNull null }
            val title = d.optString("title", id).ifBlank { id }
            Title(
                key = "ia:$id", title = title, year = d.optString("year").take(4).toIntOrNull() ?: 0,
                kind = "MOVIE", runtimeMin = 0, rating = 0f, genres = emptyList(),
                overview = flattenField(d, "description").take(600), posterUrl = "https://archive.org/services/img/$id",
                source = name, watchUrl = null, languages = listOf(flattenField(d, "language")).filter { it.isNotBlank() }
            )
        }.filter { !Ranking.isJunkTitle(it.title) && Query.similarity(query, it.title) >= 0.45 }
            .sortedByDescending { Query.similarity(query, it.title) }
            .take(maxVerify)

        val gate = Semaphore(4)
        coroutineScope {
            base.map { t -> async { gate.withPermit { verify(t) } } }.awaitAll().filterNotNull()
        }
    }

    /** Metadata fields can be a string or an array of strings. */
    private fun flattenField(d: JSONObject, key: String): String {
        val a = d.optJSONArray(key)
        return if (a != null) (0 until a.length()).joinToString(" ") { a.optString(it) } else d.optString(key)
    }

    /** Returns the title with a validated playback URL and real runtime, or null when it cannot be verified as video. */
    private suspend fun verify(t: Title): Title? {
        val id = t.key.removePrefix("ia:")
        val r: Resolved? = try { resolve(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (r == null) return null
        return if (r.episodes.size >= 2)
            t.copy(kind = "SERIES", runtimeMin = 0, watchUrl = r.url, playable = true)
        else
            t.copy(kind = "MOVIE", runtimeMin = r.runtimeMin, watchUrl = r.url, playable = true)
    }

    /** Reads item metadata and returns the best playable file, its verified runtime and any SxxEyy episodes. */
    suspend fun resolve(id: String): Resolved? = withContext(Dispatchers.IO) {
        val files = JSONObject(Http.get("https://archive.org/metadata/${URLEncoder.encode(id, "UTF-8")}")).optJSONArray("files")
            ?: return@withContext null
        data class F(val name: String, val size: Long, val lenSec: Double, val derivative: Boolean)
        val vids = (0 until files.length()).mapNotNull { files.optJSONObject(it) }.mapNotNull { o ->
            val n = o.optString("name")
            if (playableExt.none { n.endsWith(it, true) } || o.optString("source") == "metadata") return@mapNotNull null
            if (Regex("""(sample|trailer)""", RegexOption.IGNORE_CASE).containsMatchIn(n)) return@mapNotNull null
            F(n, o.optString("size", "0").toLongOrNull() ?: 0L, parseLengthSeconds(o.optString("length")), o.optString("source") == "derivative")
        }
        if (vids.isEmpty()) return@withContext null
        fun urlOf(n: String) = "https://archive.org/download/$id/" + n.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        val eps = vids.mapNotNull { f -> Episodes.parse(f.name)?.let { (s, e) ->
            Episode(s, e, f.name.substringAfterLast('/').substringBeforeLast('.'), urlOf(f.name), Math.round(f.lenSec / 60.0).toInt()) } }
            .distinctBy { it.season to it.episode }
        if (eps.size >= 2) {
            val sorted = Episodes.sort(eps)
            return@withContext Resolved(sorted.first().url, 0, sorted)
        }
        val best = vids.filter { it.lenSec > 0 }.maxWithOrNull(compareBy<F>({ it.lenSec }, { if (it.derivative) 1 else 0 }, { -it.size }))
            ?: return@withContext null
        Resolved(urlOf(best.name), Math.ceil(best.lenSec / 60.0).toInt(), emptyList())
    }

    /** Back-compat helper used by the Open button. */
    suspend fun resolveFile(id: String): String? = resolve(id)?.url

    companion object {
        /** Accepts "5400.2", "5400", "1:30:00" and "90:00". Returns 0 when unknown/invalid. */
        fun parseLengthSeconds(s: String): Double {
            val t = s.trim()
            if (t.isEmpty()) return 0.0
            t.toDoubleOrNull()?.let { return if (it.isFinite() && it > 0) it else 0.0 }
            val parts = t.split(':').map { it.toDoubleOrNull() ?: return 0.0 }
            return when (parts.size) { 3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]; 2 -> parts[0] * 60 + parts[1]; else -> 0.0 }
        }
    }
}

/** Fan-out search across providers: concurrent, time-boxed, failure-isolated, de-duplicated and ranked. */
class SearchHub(private val providers: List<TitleProvider>) {
    data class Outcome(val titles: List<Title>, val intent: Query.Parsed, val errors: List<String>, val page: Int = 1)

    suspend fun search(raw: String, page: Int = 1): Outcome = coroutineScope {
        val p = Query.parse(raw)
        val cacheKey = "q:${p.text}|${p.year}|${p.season}|${p.episode}|$page"
        SearchCache.get<List<Title>>(cacheKey)?.let { return@coroutineScope Outcome(it, p, emptyList(), page) }
        val errors = ConcurrentHashMap.newKeySet<String>()
        val results = providers.map { prov ->
            async {
                try { kotlinx.coroutines.withTimeout(25_000) { prov.search(p.text, p.year, page) } }
                catch (e: CancellationException) { if (e is kotlinx.coroutines.TimeoutCancellationException) { errors.add("${prov.name}: timed out"); emptyList<Title>() } else throw e }
                catch (e: Exception) { errors.add("${prov.name}: ${e.message ?: "failed"}"); emptyList<Title>() }
            }
        }.flatMap { it.await() }

        val merged = rank(results, p)
        if (errors.isEmpty()) SearchCache.put(cacheKey, merged)
        Outcome(merged, p, errors.toList(), page)
    }

    /** Filters, de-duplicates (trivial title variants collapse) and ranks. Pure; used directly by tests. */
    fun rank(results: List<Title>, p: Query.Parsed): List<Title> = results
        .filter { t -> passesKindRules(t, p) }
        .groupBy { Ranking.canonicalTitle(it.title) + "|" + it.year + "|" + it.kind }
        .map { (_, g) -> g.maxBy { (if (it.playable) 1000 else 0) + it.overview.length + (if (it.posterUrl != null) 1 else 0) } }
        .sortedByDescending { Ranking.score(it, p) }

    /** Movies must run MORE than [MIN_MOVIE_MINUTES]; unknown runtime is rejected. Series/episodes are exempt. */
    fun passesKindRules(t: Title, p: Query.Parsed): Boolean {
        if (Query.similarity(p.text, t.title) < 0.45) return false
        if (t.kind == "MOVIE" && t.runtimeMin <= MIN_MOVIE_MINUTES) return false
        if (t.kind == "EPISODE" && p.season != null && t.season != p.season) return false
        return true
    }
}

/** Bridge kept for callers that expect a single hub factory. */
object SearchBridge {
    fun hub() = SearchHub(listOf(ArchiveProvider()))
}
