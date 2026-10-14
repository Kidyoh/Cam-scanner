package dev.kidyoh.scanly.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class PageFilter(val label: String) {
    ORIGINAL("Original"),
    MAGIC("Magic colour"),
    ENHANCE("Enhance"),
    GRAYSCALE("Greyscale"),
    BW("Black & white"),
    LIGHTEN("Lighten"),
    ;

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

/** Document image filters. All of them return a new bitmap and leave the source untouched. */
object Filters {

    fun apply(src: Bitmap, filter: PageFilter): Bitmap = when (filter) {
        PageFilter.ORIGINAL -> src.copy(Bitmap.Config.ARGB_8888, true)
        PageFilter.ENHANCE -> withMatrix(src, contrast(1.25f, 10f).also { it.postConcat(saturation(1.15f)) })
        PageFilter.GRAYSCALE -> withMatrix(src, saturation(0f).also { it.postConcat(contrast(1.2f, 6f)) })
        PageFilter.LIGHTEN -> withMatrix(src, contrast(1.05f, 35f))
        PageFilter.MAGIC -> magic(src)
        PageFilter.BW -> blackAndWhite(src)
    }

    fun applyToFile(src: File, dest: File, filter: PageFilter, quality: Float) {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: error("Couldn't read page image")
        val out = apply(bmp, filter)
        ImageUtils.write(out, dest, quality)
        bmp.recycle()
        out.recycle()
    }

    /** Small previews for the filter picker. */
    fun previews(src: File, width: Int = 320): Map<PageFilter, Bitmap> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val small = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return emptyMap()
        return PageFilter.entries.associateWith { apply(small, it) }.also { small.recycle() }
    }

    private fun withMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        return out
    }

    private fun contrast(c: Float, brightness: Float): ColorMatrix {
        val t = 128f * (1 - c) + brightness
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }

    private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

    /**
     * "Magic colour": white-balances the paper to pure white using each channel's bright
     * percentile, then boosts contrast and colour so ink and stamps pop.
     */
    private fun magic(src: Bitmap): Bitmap {
        val sample = Bitmap.createScaledBitmap(src, max(1, src.width / 8), max(1, src.height / 8), true)
        val px = IntArray(sample.width * sample.height)
        sample.getPixels(px, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== src) sample.recycle()
        val hr = IntArray(256)
        val hg = IntArray(256)
        val hb = IntArray(256)
        for (p in px) {
            hr[(p shr 16) and 0xFF]++
            hg[(p shr 8) and 0xFF]++
            hb[p and 0xFF]++
        }
        fun white(h: IntArray): Float {
            var acc = 0
            val target = px.size * 0.05
            for (v in 255 downTo 0) {
                acc += h[v]
                if (acc >= target) return max(v, 96).toFloat()
            }
            return 255f
        }
        val balance = ColorMatrix().apply {
            setScale(255f / white(hr), 255f / white(hg), 255f / white(hb), 1f)
        }
        balance.postConcat(contrast(1.35f, -8f))
        balance.postConcat(saturation(1.3f))
        return withMatrix(src, balance)
    }

    /**
     * Adaptive threshold: every pixel is compared with the average brightness of its
     * neighbourhood, so shadows and uneven lighting don't turn into black blotches.
     * The local mean is computed on a 1/8-scale copy to stay fast and memory-light.
     */
    private fun blackAndWhite(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val factor = 8
        val sw = max(1, w / factor)
        val sh = max(1, h / factor)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        // Integral image of the small greyscale copy.
        val integral = LongArray((sw + 1) * (sh + 1))
        for (y in 0 until sh) {
            var row = 0L
            for (x in 0 until sw) {
                row += luma(sp[y * sw + x])
                integral[(y + 1) * (sw + 1) + x + 1] = integral[y * (sw + 1) + x + 1] + row
            }
        }
        val r = max(2, min(sw, sh) / 16)
        val mean = IntArray(sw * sh)
        for (y in 0 until sh) {
            val y0 = max(0, y - r)
            val y1 = min(sh, y + r + 1)
            for (x in 0 until sw) {
                val x0 = max(0, x - r)
                val x1 = min(sw, x + r + 1)
                val sum = integral[y1 * (sw + 1) + x1] - integral[y0 * (sw + 1) + x1] -
                    integral[y1 * (sw + 1) + x0] + integral[y0 * (sw + 1) + x0]
                mean[y * sw + x] = (sum / ((x1 - x0) * (y1 - y0))).toInt()
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val my = min(sh - 1, y / factor) * sw
            for (x in 0 until w) {
                val m = mean[my + min(sw - 1, x / factor)]
                row[x] = if (luma(row[x]) < m - 12) BLACK else WHITE
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun luma(p: Int): Int =
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
