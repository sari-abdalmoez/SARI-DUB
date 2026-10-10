package com.saridub.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Srt {
    data class Cue(val time: String, val text: String)

    fun parse(raw: String): List<Cue> {
        val cues = ArrayList<Cue>()
        for (block in raw.replace("\r", "").split(Regex("\\n\\s*\\n"))) {
            val lines = block.lines()
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val text = lines.drop(ti + 1).joinToString("\n").trim()
            if (text.isNotEmpty()) cues.add(Cue(lines[ti].trim(), text))
        }
        return cues
    }

    fun write(cues: List<Cue>, texts: List<String>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n').append(c.time).append('\n')
            append(texts.getOrElse(i) { c.text }).append("\n\n")
        }
    }
}

/** Downloads an SRT, translates it to Arabic in batches, caches it on disk. */
object SubtitlePipeline {
    suspend fun arabicSubtitle(ctx: Context, url: String): File? = withContext(Dispatchers.IO) {
        if (!url.endsWith(".srt", ignoreCase = true)) return@withContext null
        val file = File(ctx.cacheDir, "ar_${url.hashCode()}.srt")
        if (file.exists() && file.length() > 0) return@withContext file

        val cues = Srt.parse(Net.get(url, timeout = 20000))
        if (cues.isEmpty()) return@withContext null

        val out = ArrayList<String>()
        cues.chunked(40).forEach { chunk ->
            val tr = Gemini.translateLines(chunk.map { it.text }, "Arabic")
            chunk.indices.forEach { out.add(tr.getOrElse(it) { chunk[it].text }) }
        }
        file.writeText(Srt.write(cues, out))
        file
    }
}
