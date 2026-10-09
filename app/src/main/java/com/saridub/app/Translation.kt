package com.saridub.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Outcome for one input segment. The id and original text always travel with the result. */
sealed class SegResult {
    abstract val id: Int
    abstract val original: String
    data class Ok(override val id: Int, override val original: String, val text: String, val fromCache: Boolean = false) : SegResult()
    data class Failed(override val id: Int, override val original: String, val reason: String) : SegResult()
}

/** Pure text utilities for translation; no Android dependencies so they are unit-testable on the JVM. */
object TextUtil {
    /** True when the text contains at least one letter (pure numbers, music notes and symbols are copied as-is). */
    fun needsTranslation(s: String): Boolean = s.any { it.isLetter() }

    /** Cache key: language pair + normalised text. */
    fun tmKey(src: String, dst: String, text: String): String =
        "$src>$dst|" + text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA ||
            b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            b == Character.UnicodeBlock.THAI
    }

    /** Whole-word, case-insensitive containment. Scripts written without spaces fall back to substring match. */
    fun containsTerm(text: String, term: String): Boolean {
        if (term.isBlank()) return false
        if (term.any { isCjk(it) }) return text.contains(term)
        return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    fun replaceTerm(text: String, from: String, to: String): String {
        if (from.isBlank()) return text
        if (from.any { isCjk(it) }) return text.replace(from, to)
        return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(from) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .replace(text) { to }
    }

    /** Splits a translated one-line string back into the same number of lines as the original cue (at a space near the middle). */
    fun rewrap(original: String, translated: String): String {
        val n = original.lines().size
        if (n <= 1 || translated.contains('\n')) return translated
        val words = translated.split(' ').filter { it.isNotEmpty() }
        if (words.size < n) return translated
        val total = translated.length
        val lines = ArrayList<String>(); var cur = StringBuilder()
        for (w in words) {
            if (cur.isNotEmpty() && lines.size < n - 1 && cur.length + 1 + w.length / 2 > total / n) { lines.add(cur.toString()); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        lines.add(cur.toString())
        return lines.joinToString("\n")
    }

    /** Applies glossary targets to a translation, touching only the machine rendition of terms present in the source. */
    suspend fun applyGlossary(
        source: String, translated: String, glossary: Map<String, String>,
        renditionOf: suspend (String) -> String?
    ): String {
        var t = translated
        for ((term, target) in glossary) {
            if (!containsTerm(source, term)) continue
            if (containsTerm(t, target)) continue
            val r = renditionOf(term)?.trim().orEmpty()
            if (r.isNotEmpty() && containsTerm(t, r)) t = replaceTerm(t, r, target)
        }
        return t
    }
}

/**
 * Context-aware batch translation. Neighbouring cues are translated together (one line per cue) so the engine sees the
 * surrounding conversation. Every batch is validated: if the returned line count differs, nothing from that batch is
 * trusted and each cue is retried individually. Stable ids guarantee no reordering, duplication or loss.
 */
object ContextBatcher {
    const val MAX_BATCH = 6
    const val MAX_BATCH_CHARS = 900

    fun makeBatches(items: List<Pair<Int, String>>): List<List<Pair<Int, String>>> {
        val out = ArrayList<List<Pair<Int, String>>>(); var cur = ArrayList<Pair<Int, String>>(); var chars = 0
        for (it in items) {
            if (cur.isNotEmpty() && (cur.size >= MAX_BATCH || chars + it.second.length > MAX_BATCH_CHARS)) { out.add(cur); cur = ArrayList(); chars = 0 }
            cur.add(it); chars += it.second.length
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** Splits a model reply into exactly [n] non-blank lines, or null when it cannot be mapped safely. */
    fun splitReply(reply: String, n: Int): List<String>? {
        val lines = reply.replace("\r", "").split('\n').map { it.trim() }
        val nonEmpty = lines.filter { it.isNotEmpty() }
        return if (nonEmpty.size == n) nonEmpty else null
    }

    /**
     * @param translate engine call for one string (may throw)
     * @param onBatchDone called after each batch with the cumulative number of finished items
     */
    suspend fun run(
        items: List<Pair<Int, String>>,
        translate: suspend (String) -> String,
        onBatchDone: suspend (done: Int, total: Int, results: List<SegResult>) -> Unit = { _, _, _ -> }
    ): List<SegResult> {
        val all = ArrayList<SegResult>(items.size)
        var done = 0
        for (batch in makeBatches(items)) {
            currentCoroutineContext().ensureActive()
            val res = ArrayList<SegResult>(batch.size)
            val flat = batch.map { it.second.replace("\n", " ").trim() }
            var mapped: List<String>? = null
            if (batch.size > 1) {
                mapped = try { splitReply(translate(flat.joinToString("\n")), batch.size) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { null }
            }
            batch.forEachIndexed { i, (id, orig) ->
                currentCoroutineContext().ensureActive()
                val text = mapped?.get(i) ?: try { translate(flat[i]) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { res.add(SegResult.Failed(id, orig, e.message ?: e.javaClass.simpleName)); return@forEachIndexed }
                if (text.isBlank()) res.add(SegResult.Failed(id, orig, "Engine returned empty text"))
                else res.add(SegResult.Ok(id, orig, TextUtil.rewrap(orig, text.trim())))
            }
            all.addAll(res); done += batch.size
            onBatchDone(done, items.size, res)
        }
        return all
    }
}
