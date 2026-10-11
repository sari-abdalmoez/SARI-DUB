package com.saridub.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class Line(val startMs: Long, val endMs: Long, val text: String)

object Srt {
    fun parseTime(s: String): Long {
        val m = Regex("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})").find(s.trim()) ?: return 0L
        val (h, mi, se, ms) = m.destructured
        return h.toLong() * 3_600_000 + mi.toLong() * 60_000 + se.toLong() * 1000 + ms.padEnd(3, '0').toLong()
    }

    fun formatTime(ms: Long): String {
        val h = ms / 3_600_000; val mi = ms / 60_000 % 60; val se = ms / 1000 % 60; val mm = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, mi, se, mm)
    }

    fun parse(raw: String): List<Line> {
        val out = ArrayList<Line>()
        for (block in raw.replace("\r", "").split(Regex("\\n\\s*\\n"))) {
            val lines = block.lines()
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            val text = lines.drop(ti + 1).joinToString("\n").trim()
            if (text.isNotEmpty()) out.add(Line(parseTime(parts[0]), parseTime(parts[1]), text))
        }
        return out
    }

    fun write(lines: List<Line>): String = buildString {
        lines.forEachIndexed { i, l ->
            append(i + 1).append('\n')
            append(formatTime(l.startMs)).append(" --> ").append(formatTime(l.endMs)).append('\n')
            append(l.text).append("\n\n")
        }
    }
}

object SubtitlePipeline {
    /** Downloads an SRT, translates it to Arabic in batches, returns timed Arabic lines. */
    suspend fun arabicLines(ctx: Context, url: String): List<Line> = withContext(Dispatchers.IO) {
        if (!url.endsWith(".srt", ignoreCase = true)) return@withContext emptyList()
        val file = File(ctx.cacheDir, "ar_${url.hashCode()}.srt")
        if (file.exists() && file.length() > 0) return@withContext Srt.parse(file.readText())

        val src = Srt.parse(Net.get(url, timeout = 20000))
        if (src.isEmpty()) return@withContext emptyList()
        val translated = ArrayList<String>()
        src.chunked(40).forEach { chunk ->
            val tr = Gemini.translateLines(chunk.map { it.text }, "Arabic")
            chunk.indices.forEach { translated.add(tr.getOrElse(it) { chunk[it].text }) }
        }
        val result = src.mapIndexed { i, l -> l.copy(text = translated[i]) }
        file.writeText(Srt.write(result))
        result
    }
}
