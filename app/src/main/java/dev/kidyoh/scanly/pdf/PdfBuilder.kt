package dev.kidyoh.scanly.pdf

import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import dev.kidyoh.scanly.data.PageSize
import java.io.File
import java.util.UUID
import kotlin.math.min

data class PdfPageInput(val image: File, val ocr: OcrResult?)

/**
 * Builds image-based PDFs with an invisible, correctly positioned OCR text layer, so the
 * resulting file is searchable and text can be selected/copied in any PDF reader.
 */
object PdfBuilder {
    private val systemFonts = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
    )

    fun build(
        pages: List<PdfPageInput>,
        out: File,
        pageSize: PageSize,
        jpegQuality: Float,
        maxImageSide: Int = Int.MAX_VALUE,
    ) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        try {
            write(pages, tmp, pageSize, jpegQuality, maxImageSide, useSystemFont = true)
        } catch (e: Exception) {
            // Some devices ship fonts PDFBox can't embed; the standard font always works.
            write(pages, tmp, pageSize, jpegQuality, maxImageSide, useSystemFont = false)
        }
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }

    private fun write(
        pages: List<PdfPageInput>,
        dest: File,
        pageSize: PageSize,
        quality: Float,
        maxSide: Int,
        useSystemFont: Boolean,
    ) {
        PDDocument().use { doc ->
            val font = if (useSystemFont) loadFont(doc) else PDType1Font.HELVETICA
            for (input in pages) addPage(doc, input, pageSize, quality, maxSide, font)
            doc.documentInformation.creator = "Scanly"
            doc.documentInformation.producer = "Scanly"
            doc.save(dest)
        }
    }

    private fun addPage(
        doc: PDDocument,
        input: PdfPageInput,
        pageSize: PageSize,
        quality: Float,
        maxSide: Int,
        font: PDFont,
    ) {
        val decoded = BitmapFactory.decodeFile(input.image.absolutePath) ?: return
        // Downscaling only changes how many pixels are embedded; OCR coordinates are relative.
        val bmp = ImageUtils.scaleDown(decoded, maxSide)
        if (bmp !== decoded) decoded.recycle()
        val image = JPEGFactory.createFromImage(doc, bmp, quality)
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        bmp.recycle()

        val landscape = iw > ih
        val (pw, ph) = when (pageSize) {
            PageSize.AUTO -> 595f to 595f * ih / iw
            PageSize.A4 -> if (landscape) 842f to 595f else 595f to 842f
            PageSize.LETTER -> if (landscape) 792f to 612f else 612f to 792f
        }
        val scale = min(pw / iw, ph / ih)
        val dw = iw * scale
        val dh = ih * scale
        val ox = (pw - dw) / 2
        val oy = (ph - dh) / 2

        val page = PDPage(PDRectangle(pw, ph))
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(image, ox, oy, dw, dh)
            val ocr = input.ocr ?: return@use
            if (ocr.lines.isEmpty() || ocr.width <= 0 || ocr.height <= 0) return@use
            val sx = dw / ocr.width
            val sy = dh / ocr.height
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER)
            for (line in ocr.lines) {
                val text = encodable(font, line.text.replace(Regex("\\s+"), " ").trim())
                if (text.isBlank()) continue
                val boxW = line.box.width() * sx
                val boxH = line.box.height() * sy
                if (boxW <= 0 || boxH <= 0) continue
                val fontSize = boxH * 0.9f
                val textW = runCatching { font.getStringWidth(text) / 1000f * fontSize }.getOrDefault(0f)
                if (textW <= 0f) continue
                cs.setFont(font, fontSize)
                cs.setHorizontalScaling((boxW / textW * 100f).coerceIn(5f, 1000f))
                val x = ox + line.box.left * sx
                val y = oy + dh - line.box.bottom * sy + boxH * 0.2f
                cs.setTextMatrix(Matrix.getTranslateInstance(x, y))
                runCatching { cs.showText(text) }
            }
            cs.endText()
        }
    }

    private fun loadFont(doc: PDDocument): PDFont {
        for (path in systemFonts) {
            val f = File(path)
            if (f.exists()) runCatching { return PDType0Font.load(doc, f) }
        }
        return PDType1Font.HELVETICA
    }

    /** Drops characters the font cannot encode (e.g. scripts not covered by the system font). */
    private fun encodable(font: PDFont, text: String): String {
        if (runCatching { font.encode(text) }.isSuccess) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            if (runCatching { font.encode(s) }.isSuccess) sb.append(s) else sb.append(' ')
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Writes an AES-encrypted copy of [src] that requires [password] to open. */
    fun encrypt(src: File, out: File, password: String) {
        PDDocument.load(src).use { doc ->
            val permissions = AccessPermission()
            val policy = StandardProtectionPolicy(UUID.randomUUID().toString(), password, permissions)
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(out)
        }
    }
}
