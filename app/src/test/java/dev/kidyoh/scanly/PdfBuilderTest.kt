package dev.kidyoh.scanly

import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.kidyoh.scanly.data.PageSize
import dev.kidyoh.scanly.pdf.OcrLine
import dev.kidyoh.scanly.pdf.OcrResult
import dev.kidyoh.scanly.pdf.PdfBuilder
import dev.kidyoh.scanly.pdf.PdfPageInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun init() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    private val ocr = OcrResult(
        1200,
        1600,
        listOf(OcrLine("Invoice 2026-042", Rect(120, 160, 900, 200)), OcrLine("Total due: 1,250.00", Rect(120, 260, 800, 300))),
    )

    @Test
    fun ocrJson_roundTrips() {
        assertEquals(ocr, OcrResult.fromJson(ocr.toJson()))
    }

    @Test
    fun build_createsSearchablePdf() {
        val img = TestImages.document(tmp.newFile("p.jpg"))
        val pdf = File(tmp.root, "out.pdf")
        PdfBuilder.build(listOf(PdfPageInput(img, ocr), PdfPageInput(img, null)), pdf, PageSize.A4, 0.85f)
        PDDocument.load(pdf).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals(595f, doc.getPage(0).mediaBox.width, 0.5f)
            assertEquals(842f, doc.getPage(0).mediaBox.height, 0.5f)
            val text = PDFTextStripper().getText(doc)
            assertTrue(text, text.contains("Invoice 2026-042"))
            assertTrue(text, text.contains("Total due"))
        }
    }

    @Test
    fun build_autoSizeFollowsImageAspect_andMaxSideShrinksFile() {
        val img = TestImages.document(tmp.newFile("p.jpg"), 2000, 1000)
        val big = File(tmp.root, "big.pdf")
        val small = File(tmp.root, "small.pdf")
        PdfBuilder.build(listOf(PdfPageInput(img, null)), big, PageSize.AUTO, 0.9f)
        PdfBuilder.build(listOf(PdfPageInput(img, null)), small, PageSize.AUTO, 0.5f, 600)
        PDDocument.load(big).use { assertEquals(297.5f, it.getPage(0).mediaBox.height, 0.5f) }
        assertTrue("${small.length()} < ${big.length()}", small.length() < big.length())
    }

    @Test
    fun encrypt_requiresPassword() {
        val img = TestImages.document(tmp.newFile("p.jpg"))
        val pdf = File(tmp.root, "plain.pdf")
        val locked = File(tmp.root, "locked.pdf")
        PdfBuilder.build(listOf(PdfPageInput(img, ocr)), pdf, PageSize.AUTO, 0.8f)
        PdfBuilder.encrypt(pdf, locked, "s3cret")
        try {
            PDDocument.load(locked).close()
            fail("opened without password")
        } catch (_: InvalidPasswordException) {
        }
        PDDocument.load(locked, "s3cret").use { assertEquals(1, it.numberOfPages) }
    }
}
