package dev.kidyoh.scanly.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.File
import kotlin.math.max

/** Rasterizes an existing PDF into page images so it can be edited, OCR'd and re-exported. */
object PdfImporter {
    fun render(context: Context, uri: Uri, outDir: File, maxSide: Int, quality: Float): List<File> {
        outDir.mkdirs()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return pfd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                (0 until renderer.pageCount).map { i ->
                    renderer.openPage(i).use { page ->
                        val scale = maxSide.toFloat() / max(page.width, page.height)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val f = File(outDir, "import_${System.nanoTime()}_$i.jpg")
                        ImageUtils.write(bmp, f, quality)
                        bmp.recycle()
                        f
                    }
                }
            }
        }
    }
}
