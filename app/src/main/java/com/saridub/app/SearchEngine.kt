package com.saridub.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified search: queries all providers concurrently, each with its own timeout.
 * A failing provider just contributes nothing. Results are streamed through onPartial.
 */
object SearchEngine {
    data class Scored(val title: Title, val score: Double)

    private val providers: List<TitleProvider> = listOf(TmdbProvider(), TvMazeProvider(), TraktProvider())
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Scored>>>()
    private val seenTitles = ConcurrentHashMap<String, Title>()
    private const val TTL_MS = 10 * 60_000L
    private const val PROVIDER_TIMEOUT_MS = 6_000L

    suspend fun search(raw: String, onPartial: (List<Scored>) -> Unit = {}): List<Scored> = coroutineScope {
        val q = raw.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()

        val key = Text.normalize(q)
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let {
            onPartial(it.second)
            return@coroutineScope it.second
        }

        val collected: MutableList<Title> = Collections.synchronizedList(ArrayList())
        providers.map { p ->
            async {
                val res: List<Title> = try {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { p.search(q) } ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                collected.addAll(res)
                onPartial(rank(q, collected.toList()))
            }
        }.awaitAll()

        val ranked = rank(q, collected.toList())
        if (ranked.isNotEmpty()) cache[key] = System.currentTimeMillis() to ranked
        ranked
    }

    /** Local autocomplete from titles seen in recent searches. Instant, no network. */
    fun suggest(q: String, limit: Int = 6): List<Title> {
        val nq = Text.normalize(q)
        if (nq.length < 2) return emptyList()
        val sq = Text.skeleton(q)
        return seenTitles.values.asSequence()
            .filter { Text.normalize(it.title).contains(nq) || (sq.length >= 2 && Text.skeleton(it.title).contains(sq)) }
            .sortedByDescending { it.rating }
            .take(limit)
            .toList()
    }

    fun rank(q: String, items: List<Title>): List<Scored> {
        val wantSeries = Regex("series|season|show|مسلسل|موسم|حلقة", RegexOption.IGNORE_CASE).containsMatchIn(q)
        val wantMovie = Regex("movie|film|فيلم|فلم|افلام", RegexOption.IGNORE_CASE).containsMatchIn(q)
        val year = Regex("\\b(19|20)\\d{2}\\b").find(q)?.value?.toIntOrNull()

        val scored = items.map { Scored(it, score(q, it, wantSeries, wantMovie, year)) }
            .sortedByDescending { it.score }

        // Remove duplicates from different providers: same normalised title, year and kind.
        val seen = HashSet<String>()
        val out = ArrayList<Scored>()
        for (s in scored) {
            if (s.score < 0.35) continue
            val k = Text.normalize(s.title.title) + "|" + (s.title.year ?: 0) + "|" + s.title.kind
            if (seen.add(k)) {
                out.add(s)
                if (seenTitles.size > 2000) seenTitles.clear()
                seenTitles[s.title.key] = s.title
            }
        }
        return out
    }

    private fun score(
        q: String, t: Title, wantSeries: Boolean, wantMovie: Boolean, year: Int?
    ): Double {
        val nq = Text.normalize(q)
        val nt = Text.normalize(t.title)
        var s = Text.similarity(nq, nt)

        // Cross-script match (Arabic query vs Latin title and vice versa)
        val sq = Text.skeleton(q)
        val st = Text.skeleton(t.title)
        if (sq.isNotEmpty() && st.isNotEmpty()) s = maxOf(s, Text.similarity(sq, st) * 0.95)

        t.altTitles.forEach { alt ->
            if (alt.isNotBlank()) s = maxOf(s, Text.similarity(nq, Text.normalize(alt)) * 0.95)
        }

        // Token coverage: handles extra or missing words ("the", subtitles, etc.)
        val qt = Text.tokens(q)
        val tt = Text.tokens(t.title)
        if (qt.isNotEmpty()) s = maxOf(s, qt.count { it in tt }.toDouble() / qt.size * 0.9)

        if (nq == nt) s += 0.3
        if (nq.length >= 3 && nt.startsWith(nq)) s += 0.05
        if (wantSeries && t.kind == "SERIES") s += 0.1
        if (wantMovie && t.kind == "MOVIE") s += 0.1
        if (year != null && t.year == year) s += 0.15
        s += (t.rating / 10.0) * 0.05
        return s
    }
}
