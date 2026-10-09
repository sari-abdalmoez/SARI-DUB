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

/** Pure geometry/colour heuristics (JVM-testable) used to decide whether an OCR block is speech-bubble dialogue. */
object BubbleHeuristics {
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w get() = r - l
        val h get() = b - t
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
    }

    /** Mean and standard deviation of luminance values (0..255). */
    fun stats(values: IntArray): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 0.0
        val m = values.average()
        return m to Math.sqrt(values.map { (it - m) * (it - m) }.average())
    }

    /**
     * A block counts as bubble dialogue when its surroundings are bright (or uniformly dark) and flat, the block has a
     * plausible size, contains letters, and is not a page number / lone symbol.
     * @param ringLum luminance samples of a ring just outside the text box
     */
    fun isDialogue(text: String, box: Box, pageW: Int, pageH: Int, ringLum: IntArray): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        if (text.trim().all { it.isDigit() || it in "-–—.,/ " }) return false            // page numbers
        if (box.w < pageW / 60 || box.h < pageH / 80) return false                          // specks
        if (box.w > pageW * 0.7 && box.h > pageH * 0.5) return false                         // whole-page text / artwork
        if (letters <= 2 && text.length <= 3 && box.h > pageH / 10) return false            // giant onomatopoeia glyphs
        val (mean, sd) = stats(ringLum)
        val flat = sd < 38.0
        val brightOrDark = mean > 170.0 || mean < 45.0
        return flat && brightOrDark
    }

    /** Reading order: rows top-to-bottom, within a row right-to-left (manga) or left-to-right. */
    fun readingOrder(boxes: List<Box>, rightToLeft: Boolean): List<Int> {
        if (boxes.isEmpty()) return emptyList()
        val rowTol = (boxes.map { it.h }.average() * 1.2).toInt().coerceAtLeast(1)
        val idx = boxes.indices.sortedBy { boxes[it].cy }
        val rows = ArrayList<MutableList<Int>>()
        for (i in idx) {
            val row = rows.lastOrNull()
            if (row != null && Math.abs(boxes[row[0]].cy - boxes[i].cy) <= rowTol) row.add(i) else rows.add(mutableListOf(i))
        }
        return rows.flatMap { row -> if (rightToLeft) row.sortedByDescending { boxes[it].cx } else row.sortedBy { boxes[it].cx } }
    }

    fun isRtlLanguage(code: String) = code == "ar" || code == "fa" || code == "ur" || code == "he"
    fun isRightToLeftReading(sourceLang: String) = sourceLang == "ja"
}

/** Low-memory, deterministic manga page processor. It never fabricates scene details. */
object MangaProcessor {
    data class Result(val output: File, val sourceLanguage: String, val blocks: Int, val skipped: Int = 0, val failed: Int = 0)

    private const val OCR_LONG_EDGE = 2200
    private const val OUTPUT_LONG_EDGE = 4096
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_PIXELS = 60_000_000L

    private fun sampleBitmap(context: Context, uri: Uri, maxLong: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val size = MediaAnalyzer.sizeOf(context, uri)
        if (size > MAX_FILE_BYTES) throw PipelineError("MANGA_IMAGE", "Image is too large (${size / 1048576} MB). Limit is ${MAX_FILE_BYTES / 1048576} MB.")
        (context.contentResolver.openInputStream(uri) ?: throw PipelineError("MANGA_IMAGE", "Cannot open the selected image. Re-select it.")).use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw PipelineError("MANGA_IMAGE", "Not a readable image file")
        if (opts.outWidth.toLong() * opts.outHeight > MAX_PIXELS) throw PipelineError("MANGA_IMAGE", "Image dimensions are too large (${opts.outWidth}x${opts.outHeight})")
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > maxLong) sample *= 2
        val out = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            (context.contentResolver.openInputStream(uri) ?: throw PipelineError("MANGA_IMAGE", "Cannot open the selected image")).use {
                BitmapFactory.decodeStream(it, null, out)
            } ?: throw PipelineError("MANGA_IMAGE", "Could not decode image")
        } catch (e: OutOfMemoryError) {
            throw PipelineError("MANGA_MEMORY", "Not enough memory to open this page. Close other apps or use a smaller image.")
        }
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

    /** Luminance samples on a thin ring just outside the box (used to judge bubble background). */
    private fun ringLuminance(bitmap: Bitmap, box: android.graphics.Rect): IntArray {
        val pad = max(3, min(box.width(), box.height()) / 10)
        val l = (box.left - pad).coerceIn(0, bitmap.width - 1); val t = (box.top - pad).coerceIn(0, bitmap.height - 1)
        val r = (box.right + pad).coerceIn(0, bitmap.width - 1); val b = (box.bottom + pad).coerceIn(0, bitmap.height - 1)
        val out = ArrayList<Int>()
        fun add(x: Int, y: Int) { val c = bitmap.getPixel(x, y); out.add((0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()) }
        for (x in l..r step max(1, (r - l) / 32)) { add(x, t); add(x, b) }
        for (y in t..b step max(1, (b - t) / 32)) { add(l, y); add(r, y) }
        return out.toIntArray()
    }

    private fun removeText(canvas: Canvas, bitmap: Bitmap, box: android.graphics.Rect) {
        val pad = max(4, min(box.width(), box.height()) / 12)
        val r = android.graphics.Rect(
            (box.left - pad).coerceAtLeast(0),
            (box.top - pad).coerceAtLeast(0),
            (box.right + pad).coerceAtMost(bitmap.width),
            (box.bottom + pad).coerceAtMost(bitmap.height)
        )
        val (_, sd) = BubbleHeuristics.stats(ringLuminance(bitmap, r))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = averageEdge(bitmap, r) }
        // Flat bubble: fill the text area (rounded, so bubble outlines survive). Busy art: tight fill of the text box only.
        if (sd < 38.0) canvas.drawRoundRect(RectF(r), pad.toFloat(), pad.toFloat(), p) else canvas.drawRect(box, p)
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

    /**
     * @param dialogueOnly translate only blocks that look like speech-bubble dialogue; uncertain blocks keep the original art.
     * @param rightToLeft reading order for dialogue context (manga default); pass false for left-to-right comics.
     */
    suspend fun process(
        context: Context, uri: Uri, targetLang: String, output: File,
        dialogueOnly: Boolean = true, rightToLeft: Boolean = true
    ): Result = withContext(Dispatchers.Default) {
        var source: Bitmap? = null
        var ocrBitmap: Bitmap? = null
        var outBmp: Bitmap? = null
        var translator: SegTranslator? = null
        val tmp = File(output.path + ".part")
        try {
            val src = sampleBitmap(context, uri, OUTPUT_LONG_EDGE); source = src
            val (ocr, s) = resizeForOcr(src); ocrBitmap = ocr
            val vision = recognizerResults(ocr) ?: throw PipelineError("MANGA_OCR", "No text was detected on this page")
            val sourceText = vision.text.trim()
            val det = LanguageDetector.detect(sourceText, 0.4f)
                ?: throw PipelineError("MANGA_LANG", "Could not reliably detect the page language")
            val detected = det.code
            if (!TranslationModels.valid(detected) || !TranslationModels.valid(targetLang))
                throw PipelineError("MANGA_LANG", "Unsupported translation pair: $detected -> $targetLang")
            for (lang in listOf(detected, targetLang)) {
                if (!TranslationModels.isDownloaded(lang))
                    throw PipelineError("MISSING_MODEL", "Translation model '$lang' is not installed. Download it from Settings > Models.")
            }
            if (detected != targetLang) translator = SegTranslator(detected, targetLang)
            val bmp = try { src.copy(Bitmap.Config.ARGB_8888, true) }
                catch (e: OutOfMemoryError) { throw PipelineError("MANGA_MEMORY", "Not enough memory to edit this page") }
            outBmp = bmp
            val canvas = Canvas(bmp)

            // Collect blocks in page coordinates, then classify as dialogue / other.
            data class Blk(val rect: android.graphics.Rect, val text: String, val dialogue: Boolean)
            val blks = vision.textBlocks.mapNotNull { block ->
                val b = block.boundingBox ?: return@mapNotNull null
                val rect = android.graphics.Rect(
                    (b.left / s).toInt().coerceIn(0, bmp.width - 1), (b.top / s).toInt().coerceIn(0, bmp.height - 1),
                    (b.right / s).toInt().coerceIn(1, bmp.width), (b.bottom / s).toInt().coerceIn(1, bmp.height))
                val text = block.text.trim()
                if (text.isBlank() || rect.width() < 2 || rect.height() < 2) return@mapNotNull null
                val dlg = BubbleHeuristics.isDialogue(text, BubbleHeuristics.Box(rect.left, rect.top, rect.right, rect.bottom),
                    bmp.width, bmp.height, ringLuminance(bmp, rect))
                Blk(rect, text, dlg)
            }
            val order = BubbleHeuristics.readingOrder(blks.map { BubbleHeuristics.Box(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom) }, rightToLeft)
            val ordered = order.map { blks[it] }
            val chosen = if (dialogueOnly) ordered.filter { it.dialogue } else ordered.filter { TextUtil.needsTranslation(it.text) }
            val skipped = ordered.size - chosen.size

            // Translate in reading order with neighbouring bubbles as context; ids are positions in `chosen`.
            val results: Map<Int, SegResult> = if (translator == null) emptyMap() else {
                val tr = translator!!
                ContextBatcher.run(chosen.mapIndexed { i, b -> i to b.text.replace("\n", " ") }, { tr.translate(it) })
                    .associateBy { it.id }
            }
            var drawn = 0; var failed = 0
            chosen.forEachIndexed { i, b ->
                coroutineContext.ensureActive()
                val translated = if (translator == null) b.text else when (val r = results[i]) {
                    is SegResult.Ok -> r.text
                    else -> { failed++; null }
                }
                if (translated == null) return@forEachIndexed            // keep original art on failure
                removeText(canvas, bmp, b.rect)
                drawTranslation(canvas, bmp, b.rect, translated)
                drawn++
            }

            output.parentFile?.mkdirs()
            tmp.delete()
            tmp.outputStream().use { os ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) throw PipelineError("MANGA_EXPORT", "Could not write translated page")
            }
            output.delete()
            if (!tmp.renameTo(output)) throw PipelineError("MANGA_EXPORT", "Could not finalise translated page")
            Result(output, detected, drawn, skipped, failed)
        } catch (e: OutOfMemoryError) {
            throw PipelineError("MANGA_MEMORY", "Ran out of memory on this page; skipped. Try a smaller image.")
        } finally {
            tmp.delete()                                   // never leave a half-written file behind
            translator?.close()
            if (ocrBitmap != null && ocrBitmap !== source && !ocrBitmap.isRecycled) ocrBitmap.recycle()
            if (source != null && !source.isRecycled) source.recycle()
            if (outBmp != null && !outBmp.isRecycled) outBmp.recycle()
        }
    }
}
