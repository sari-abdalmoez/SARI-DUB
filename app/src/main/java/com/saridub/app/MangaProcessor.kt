package com.saridub.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/** Low-memory, deterministic manga page processor. It never fabricates scene details. */
object MangaProcessor {
    data class Result(val output: File, val sourceLanguage: String, val blocks: Int)

    private const val OCR_LONG_EDGE = 2200
    private const val OUTPUT_LONG_EDGE = 4096

    private fun sampleBitmap(context: Context, uri: Uri, maxLong: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > maxLong) sample *= 2
        val out = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, out)
        } ?: throw PipelineError("MANGA_IMAGE", "Could not decode image")
    }

    private fun resizeForOcr(src: Bitmap): Pair<Bitmap, Float> {
        val m = max(src.width, src.height)
        if (m <= OCR_LONG_EDGE) return src to 1f
        val s = OCR_LONG_EDGE.toFloat() / m
        val b = Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
        return b to s
    }

    private suspend fun recognizerResults(bitmap: Bitmap): Text? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizers = listOf<() -> TextRecognizer>(
            { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) },
            { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) },
            { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) },
            { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) },
            { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }
        )
        var best: Text? = null
        var bestScore = 0
        for (make in recognizers) {
            coroutineContext.ensureActive()
            val recognizer = make()
            try {
                val r = recognizer.process(image).awaitT()
                val score = r?.text?.count { !it.isWhitespace() } ?: 0
                if (score > bestScore) { bestScore = score; best = r }
            } finally {
                recognizer.close()
            }
            if (bestScore > 40) break
        }
        return best
    }

    private fun averageEdge(bitmap: Bitmap, box: android.graphics.Rect): Int {
        val l = max(0, box.left); val t = max(0, box.top)
        val r = min(bitmap.width - 1, box.right); val b = min(bitmap.height - 1, box.bottom)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        fun add(x: Int, y: Int) {
            val c = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++
        }
        for (x in l..r step max(1, (r - l) / 24)) { add(x, t); add(x, b) }
        for (y in t..b step max(1, (b - t) / 24)) { add(l, y); add(r, y) }
        if (n == 0) return Color.WHITE
        return Color.rgb((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    private fun removeText(canvas: Canvas, bitmap: Bitmap, box: android.graphics.Rect) {
        val pad = max(4, min(box.width(), box.height()) / 12)
        val r = android.graphics.Rect(
            (box.left - pad).coerceAtLeast(0),
            (box.top - pad).coerceAtLeast(0),
            (box.right + pad).coerceAtMost(bitmap.width),
            (box.bottom + pad).coerceAtMost(bitmap.height)
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = averageEdge(bitmap, r) }
        canvas.drawRect(r, p)
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var line = ""
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) line = candidate
            else { out.add(line); line = w }
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    private fun drawTranslation(canvas: Canvas, bitmap: Bitmap, box: android.graphics.Rect, text: String) {
        if (text.isBlank()) return
        val bg = averageEdge(bitmap, box)
        val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg))
        var size = (box.height() * 0.42f).coerceIn(14f, 96f)
        val maxW = max(20f, box.width() * 0.90f)
        val maxH = max(20f, box.height() * 0.90f)
        var lines: List<String> = emptyList()
        var paint: Paint
        repeat(8) {
            paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (lum > 145) Color.BLACK else Color.WHITE
                textSize = size
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                isSubpixelText = true
            }
            lines = wrap(text.replace("\n", " "), paint, maxW)
            if (lines.size * size * 1.18f <= maxH) return@repeat
            size *= 0.84f
        }
        paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (lum > 145) Color.BLACK else Color.WHITE
            textSize = size
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
        }
        lines = wrap(text.replace("\n", " "), paint, maxW)
        val lh = size * 1.18f
        val total = lines.size * lh
        var y = box.centerY() - total / 2f - paint.ascent()
        for (line in lines) {
            canvas.drawText(line, box.centerX().toFloat(), y, paint)
            y += lh
        }
    }

    suspend fun process(context: Context, uri: Uri, targetLang: String, output: File): Result = withContext(Dispatchers.Default) {
        val source = sampleBitmap(context, uri, OUTPUT_LONG_EDGE)
        val (ocrBitmap, s) = resizeForOcr(source)
        val vision = recognizerResults(ocrBitmap) ?: throw PipelineError("MANGA_OCR", "No text was detected on this page")
        val sourceText = vision.text.trim()
        val detected = LanguageDetector.detect(sourceText)?.code ?: throw PipelineError("MANGA_LANG", "Could not detect the page language")
        if (!TranslationModels.valid(detected) || !TranslationModels.valid(targetLang))
            throw PipelineError("MANGA_LANG", "Unsupported translation pair: $detected -> $targetLang")
        for (lang in listOf(detected, targetLang)) {
            if (!TranslationModels.isDownloaded(lang))
                throw PipelineError("MISSING_MODEL", "Translation model '$lang' is not installed. Download it from Settings > Models.")
        }
        val translator = if (detected == targetLang) null else SegTranslator(detected, targetLang)
        try {
            val outBmp = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(outBmp)
            val blocks = vision.textBlocks.mapNotNull { block ->
                val b = block.boundingBox ?: return@mapNotNull null
                val mapped = android.graphics.Rect(
                    (b.left / s).toInt().coerceIn(0, outBmp.width - 1),
                    (b.top / s).toInt().coerceIn(0, outBmp.height - 1),
                    (b.right / s).toInt().coerceIn(1, outBmp.width),
                    (b.bottom / s).toInt().coerceIn(1, outBmp.height)
                )
                Triple(mapped, block.text.trim(), mapped.height())
            }.filter { it.second.isNotBlank() }
            for ((box, text, _) in blocks) {
                coroutineContext.ensureActive()
                val translated = translator?.translate(text) ?: text
                removeText(canvas, outBmp, box)
                drawTranslation(canvas, outBmp, box, translated)
            }
            output.parentFile?.mkdirs()
            output.delete()
            output.outputStream().use { os ->
                if (!outBmp.compress(Bitmap.CompressFormat.PNG, 100, os))
                    throw PipelineError("MANGA_EXPORT", "Could not write translated page")
            }
            if (ocrBitmap !== source) ocrBitmap.recycle()
            if (!source.isRecycled) source.recycle()
            Result(output, detected, blocks.size)
        } finally {
            translator?.close()
        }
    }
}
