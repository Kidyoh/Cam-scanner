package dev.kidyoh.scanly.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import kotlin.math.min

object Composer {
    // A4 at 300 dpi
    private const val A4_W = 2480
    private const val A4_H = 3508

    // ID-1 card (85.6 × 54 mm) at 300 dpi: printed at true size, like a photocopy.
    private const val CARD_W = 1011
    private const val CARD_H = 638

    /** Lays out the front and back of an ID card on one A4 page, at real-world size. */
    fun idCard(sides: List<File>, dest: File, quality: Float) {
        val page = Bitmap.createBitmap(A4_W, A4_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val tops = listOf(A4_H * 0.16f, A4_H * 0.54f)
        sides.take(2).forEachIndexed { i, file ->
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed
            // Cards photographed in portrait are turned to landscape.
            val card = if (bmp.height > bmp.width) ImageUtils.rotate(bmp, 90f).also { bmp.recycle() } else bmp
            val scale = min(CARD_W.toFloat() / card.width, CARD_H.toFloat() / card.height)
            val w = card.width * scale
            val h = card.height * scale
            val left = (A4_W - w) / 2
            canvas.drawBitmap(card, null, RectF(left, tops[i], left + w, tops[i] + h), paint)
            card.recycle()
        }
        ImageUtils.write(page, dest, quality)
        page.recycle()
    }

    /**
     * Draws [stamp] (e.g. a signature with transparent background) onto [target] at a
     * position given as fractions of the target's width/height.
     */
    fun stamp(target: File, dest: File, stamp: Bitmap, left: Float, top: Float, width: Float, quality: Float) {
        val src = BitmapFactory.decodeFile(target.absolutePath) ?: error("Couldn't read page image")
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        src.recycle()
        val w = width * bmp.width
        val h = w * stamp.height / stamp.width
        val l = left * bmp.width
        val t = top * bmp.height
        Canvas(bmp).drawBitmap(stamp, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        ImageUtils.write(bmp, dest, quality)
        bmp.recycle()
    }
}
