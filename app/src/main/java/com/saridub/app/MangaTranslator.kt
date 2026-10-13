package com.saridub.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Replaces the original text inside each bubble with the translation.
 * The bubble's own background colour is sampled from the original, so the old text disappears
 * and the new text sits inside the bubble. No overlay rectangles are drawn on top of the old text.
 */
object MangaTranslator {

    suspend fun translatePage(src: Bitmap, target: String): Bitmap {
        val jpeg = ByteArrayOutputStream().also { src.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val bubbles = Gemini.mangaBubbles(jpeg, target)

        return withContext(Dispatchers.Default) {
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val w = out.width.toFloat()
            val h = out.height.toFloat()
            val bounds = RectF(0f, 0f, w, h)

            for (b in bubbles) {
                if (b.translation.isBlank()) continue
                val r = RectF(
                    (b.box[1] / 1000.0 * w).toFloat(),
                    (b.box[0] / 1000.0 * h).toFloat(),
                    (b.box[3] / 1000.0 * w).toFloat(),
                    (b.box[2] / 1000.0 * h).toFloat()
                )
                // Erase a box larger than the detected text, so no old letters remain at the edge.
                val pad = maxOf(r.width(), r.height()) * 0.12f + 4f
                val erase = RectF(r).apply { inset(-pad, -pad) }
                if (!erase.intersect(bounds)) continue

                val bg = ringColor(src, erase)
                canvas.drawRect(erase, Paint().apply { color = bg; style = Paint.Style.FILL })
                drawFitted(canvas, b.translation, r, if (luma(bg) > 140) Color.BLACK else Color.WHITE)
            }
            out
        }
    }

    private fun luma(c: Int): Double = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    /** Median colour of a ring just outside the rectangle: the bubble's own background. */
    private fun ringColor(bmp: Bitmap, r: RectF): Int {
        val reds = ArrayList<Int>(); val greens = ArrayList<Int>(); val blues = ArrayList<Int>()
        val x0 = r.left.toInt(); val x1 = (r.right - 1).toInt()
        val y0 = r.top.toInt(); val y1 = (r.bottom - 1).toInt()
        val step = max(2, ((r.width() + r.height()) / 40).toInt())
        fun add(x: Int, y: Int) {
            if (x in 0 until bmp.width && y in 0 until bmp.height) {
                val p = bmp.getPixel(x, y)
                reds.add(Color.red(p)); greens.add(Color.green(p)); blues.add(Color.blue(p))
            }
        }
        var x = x0
        while (x <= x1) { add(x, y0 - 2); add(x, y1 + 2); x += step }
        var y = y0
        while (y <= y1) { add(x0 - 2, y); add(x1 + 2, y); y += step }
        if (reds.isEmpty()) return Color.WHITE
        return Color.rgb(reds.sorted()[reds.size / 2], greens.sorted()[greens.size / 2], blues.sorted()[blues.size / 2])
    }

    /** Shrinks text until it fits inside the rectangle, wrapping lines and handling RTL Arabic. */
    private fun drawFitted(canvas: Canvas, text: String, r: RectF, color: Int) {
        val width = max(10, r.width().toInt())
        var size = 36f
        while (true) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                typeface = Typeface.DEFAULT_BOLD
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            if (layout.height <= r.height() || size <= 9f) {
                canvas.save()
                canvas.translate(r.left, r.top + (r.height() - layout.height) / 2f)
                layout.draw(canvas)
                canvas.restore()
                return
            }
            size -= 2f
        }
    }
}

/** Decodes a gallery image, downscaled to limit memory on budget phones. */
fun loadBitmap(ctx: Context, uri: Uri, maxWidth: Int = 1080): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxWidth * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts)!! }
}
