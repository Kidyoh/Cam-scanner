package dev.kidyoh.scanly

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/** Generates a fake "document photo": off-white paper, a shadow gradient and dark text bars. */
object TestImages {
    fun document(file: File, width: Int = 1200, height: Int = 1600): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(225, 215, 195))
        val shadow = Paint().apply { color = Color.argb(70, 0, 0, 0) }
        c.drawRect(0f, 0f, width * 0.3f, height.toFloat(), shadow)
        val ink = Paint().apply { color = Color.rgb(30, 30, 40) }
        for (i in 0 until 12) {
            val top = height * 0.1f + i * height * 0.06f
            c.drawRect(width * 0.1f, top, width * 0.85f, top + height * 0.02f, ink)
        }
        // Camera-like paper grain, so JPEG sizes behave like real photos.
        val rnd = java.util.Random(42)
        val row = IntArray(width)
        for (y in 0 until height) {
            bmp.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val n = rnd.nextInt(25) - 12
                val p = row[x]
                row[x] = Color.rgb(
                    (Color.red(p) + n).coerceIn(0, 255),
                    (Color.green(p) + n).coerceIn(0, 255),
                    (Color.blue(p) + n).coerceIn(0, 255),
                )
            }
            bmp.setPixels(row, 0, width, 0, y, width, 1)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return file
    }
}
