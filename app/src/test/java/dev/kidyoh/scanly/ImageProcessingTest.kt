package dev.kidyoh.scanly

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.kidyoh.scanly.pdf.Composer
import dev.kidyoh.scanly.pdf.Filters
import dev.kidyoh.scanly.pdf.ImageUtils
import dev.kidyoh.scanly.pdf.PageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ImageProcessingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun size(f: File) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, this)
    }.let { it.outWidth to it.outHeight }

    @Test
    fun normalize_savesImageAndKeepsAspectRatio() {
        val src = TestImages.document(tmp.newFile("in.jpg"), 3000, 4000)
        val out = File(tmp.root, "out.jpg")
        assertTrue(ImageUtils.normalize(context, Uri.fromFile(src), out, 2000, 0.9f))
        assertEquals(1500 to 2000, size(out))
    }

    @Test
    fun normalize_keepsSmallImagesAtFullResolution() {
        val src = TestImages.document(tmp.newFile("in.jpg"), 800, 600)
        val out = File(tmp.root, "out.jpg")
        assertTrue(ImageUtils.normalize(context, Uri.fromFile(src), out, 8000, 0.9f))
        assertEquals(800 to 600, size(out))
    }

    @Test
    fun normalize_returnsFalseForMissingFile() {
        assertTrue(!ImageUtils.normalize(context, Uri.fromFile(File(tmp.root, "nope.jpg")), File(tmp.root, "o.jpg"), 2000, 0.9f))
    }

    @Test
    fun blackAndWhite_producesOnlyPureBlackAndWhite_andKeepsText() {
        val src = BitmapFactory.decodeFile(TestImages.document(tmp.newFile("in.jpg")).absolutePath)
        val out = Filters.apply(src, PageFilter.BW)
        val px = IntArray(out.width * out.height).also { out.getPixels(it, 0, out.width, 0, 0, out.width, out.height) }
        assertTrue(px.all { it == Color.BLACK || it == Color.WHITE })
        val black = px.count { it == Color.BLACK }.toDouble() / px.size
        // Text bars cover ~13% of the page; the shadow must not turn into a black block.
        assertTrue("black ratio $black", black in 0.05..0.25)
    }

    @Test
    fun grayscale_hasNoColour() {
        val src = BitmapFactory.decodeFile(TestImages.document(tmp.newFile("in.jpg")).absolutePath)
        val out = Filters.apply(src, PageFilter.GRAYSCALE)
        val p = out.getPixel(out.width - 10, 10)
        assertEquals(Color.red(p).toFloat(), Color.green(p).toFloat(), 2f)
        assertEquals(Color.green(p).toFloat(), Color.blue(p).toFloat(), 2f)
    }

    @Test
    fun magic_whitensPaper() {
        val src = BitmapFactory.decodeFile(TestImages.document(tmp.newFile("in.jpg")).absolutePath)
        val out = Filters.apply(src, PageFilter.MAGIC)
        val before = src.getPixel(src.width - 10, 10)
        val after = out.getPixel(out.width - 10, 10)
        assertTrue(Color.blue(after) > Color.blue(before) + 20)
        assertTrue(Color.red(after) >= 240)
    }

    @Test
    fun allFilters_haveSameSizeAsSource() {
        val f = TestImages.document(tmp.newFile("in.jpg"), 640, 480)
        val previews = Filters.previews(f, 320)
        assertEquals(PageFilter.entries.size, previews.size)
        previews.values.forEach { assertEquals(320, it.width) }
    }

    @Test
    fun idCard_isA4AtTrueSize() {
        val front = TestImages.document(tmp.newFile("f.jpg"), 1000, 630)
        val back = TestImages.document(tmp.newFile("b.jpg"), 630, 1000)
        val out = File(tmp.root, "id.jpg")
        Composer.idCard(listOf(front, back), out, 0.9f)
        assertEquals(2480 to 3508, size(out))
    }

    @Test
    fun stamp_drawsSignatureAtRequestedSpot() {
        val page = TestImages.document(tmp.newFile("p.jpg"), 1000, 1000)
        val sig = android.graphics.Bitmap.createBitmap(100, 50, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.BLUE) }
        val out = File(tmp.root, "s.jpg")
        Composer.stamp(page, out, sig, left = 0.5f, top = 0.9f, width = 0.2f, quality = 0.95f)
        val bmp = BitmapFactory.decodeFile(out.absolutePath)
        val p = bmp.getPixel(600, 945)
        assertTrue("expected blue, got ${Integer.toHexString(p)}", Color.blue(p) > 200 && Color.red(p) < 60)
    }
}
