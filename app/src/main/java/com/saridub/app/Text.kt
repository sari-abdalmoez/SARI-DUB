package com.saridub.app

import java.text.Normalizer

/** Text normalisation, Arabic folding, and cross-script (Arabic ↔ Latin) consonant matching. */
object Text {
    private val MARKS = Regex("\\p{Mn}+")
    private val NOISE = Regex("[^\\p{L}\\p{N}\\s]")
    private val SPACES = Regex("\\s+")

    private val AR_FOLD = mapOf('أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ى' to 'ي', 'ة' to 'ه', 'ؤ' to 'و', 'ئ' to 'ي')

    /** Phonetic approximation of Arabic letters into Latin consonants. Vowels are dropped by skeleton(). */
    private val AR_TO_LATIN = mapOf(
        'ب' to 'b', 'ت' to 't', 'ث' to 't', 'ج' to 'j', 'ح' to 'h', 'خ' to 'k', 'د' to 'd', 'ذ' to 'd',
        'ر' to 'r', 'ز' to 'z', 'س' to 's', 'ش' to 's', 'ص' to 's', 'ض' to 'd', 'ط' to 't', 'ظ' to 'z',
        'ع' to ' ', 'غ' to 'g', 'ف' to 'f', 'ق' to 'k', 'ك' to 'k', 'ل' to 'l', 'م' to 'm', 'ن' to 'n',
        'ه' to 'h', 'و' to 'w', 'ي' to 'y', 'ا' to ' ', 'ء' to ' '
    )

    fun normalize(s: String): String {
        val base = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
        val folded = buildString { for (ch in base) append(AR_FOLD[ch] ?: ch) }
        return folded.replace(NOISE, " ").replace(SPACES, " ").trim()
    }

    fun isArabic(s: String): Boolean = s.any { it in '\u0600'..'\u06FF' }

    /** Consonant skeleton: "انترستيلر" and "Interstellar" both become "ntrstlr". */
    fun skeleton(s: String): String {
        val sb = StringBuilder()
        for (ch in normalize(s)) {
            val c = AR_TO_LATIN[ch] ?: ch.lowercaseChar()
            if (c == ' ') continue
            if (!c.isLetter() || c in "aeiouy") continue
            if (sb.isEmpty() || sb.last() != c) sb.append(c)
        }
        return sb.toString()
    }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** 1.0 = identical, 0.0 = completely different. */
    fun similarity(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        if (m == 0) return 1.0
        return 1.0 - lev(a, b).toDouble() / m
    }

    fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.isNotBlank() }.toSet()
}
