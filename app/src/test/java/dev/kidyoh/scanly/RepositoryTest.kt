package dev.kidyoh.scanly

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dev.kidyoh.scanly.data.AppDatabase
import dev.kidyoh.scanly.data.DocumentRepository
import dev.kidyoh.scanly.data.SettingsStore
import dev.kidyoh.scanly.pdf.OcrEngine
import dev.kidyoh.scanly.pdf.PageFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class RepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var repo: DocumentRepository
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val settings = SettingsStore(context).apply {
            // ML Kit can't run on the JVM, and MediaStore isn't backed by real storage here.
            update { it.copy(ocrEnabled = false, autoSavePdf = false, autoSaveImages = false) }
        }
        repo = DocumentRepository(context, db.documentDao(), settings, OcrEngine(context))
    }

    @After fun tearDown() = db.close()

    private var counter = 0
    private fun scans(n: Int) = (1..n).map { Uri.fromFile(TestImages.document(File(tmp.root, "scan${counter++}.jpg"))) }
    private suspend fun pages(id: Long) = db.documentDao().getPages(id)
    private suspend fun doc(id: Long) = db.documentDao().getDocument(id)

    @Test
    fun scanning_savesPagesAndPdf() = runBlocking {
        val id = repo.createFromImages(scans(3))
        val d = doc(id)!!
        assertEquals(3, d.pageCount)
        assertTrue(File(d.pdfPath).length() > 0)
        assertTrue(File(d.thumbnailPath!!).exists())
        pages(id).forEach { assertTrue(File(it.imagePath).exists()) }
        PDDocument.load(File(d.pdfPath)).use { assertEquals(3, it.numberOfPages) }
        assertEquals(listOf(d.id), repo.observeDocuments("", false).first().map { it.id })
    }

    @Test
    fun failedScan_leavesNoEmptyDocumentBehind() = runBlocking {
        try {
            repo.createFromImages(listOf(Uri.fromFile(File(tmp.root, "missing.jpg"))))
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(repo.observeDocuments("", false).first().isEmpty())
    }

    @Test
    fun addReorderRotateAndDeletePages() = runBlocking {
        val id = repo.createFromImages(scans(2))
        repo.addPages(id, scans(1))
        assertEquals(3, doc(id)!!.pageCount)

        val before = pages(id).map { it.id }
        repo.movePage(before[2], -2)
        assertEquals(listOf(before[2], before[0], before[1]), pages(id).map { it.id })

        val first = pages(id).first()
        repo.rotatePage(first.id)
        val rotated = pages(id).first()
        assertNotEquals(first.imagePath, rotated.imagePath)
        assertFalse(File(first.imagePath).exists())

        repo.deletePage(rotated.id)
        assertEquals(2, doc(id)!!.pageCount)
        assertEquals(listOf(0, 1), pages(id).map { it.position })
    }

    @Test
    fun unreadablePagesAreSkipped_restAreSaved() = runBlocking {
        val id = repo.createFromImages(scans(2) + Uri.fromFile(File(tmp.root, "missing.jpg")))
        assertEquals(2, doc(id)!!.pageCount)
    }

    @Test
    fun deletingLastPage_deletesDocument() = runBlocking {
        val id = repo.createFromImages(scans(1))
        repo.deletePage(pages(id).single().id)
        assertNull(doc(id))
    }

    @Test
    fun filters_areNonDestructive() = runBlocking {
        val id = repo.createFromImages(scans(2))
        val ids = pages(id).map { it.id }
        repo.applyFilter(ids, PageFilter.BW)
        val bw = pages(id)
        assertTrue(bw.all { it.filter == "BW" && it.originalPath != null })
        val original = bw.first().originalPath

        repo.applyFilter(ids, PageFilter.MAGIC)
        assertEquals(original, pages(id).first().originalPath)

        repo.applyFilter(ids, PageFilter.ORIGINAL)
        assertTrue(pages(id).all { it.filter == "ORIGINAL" })
    }

    @Test
    fun mergeAndDuplicate() = runBlocking {
        val a = repo.createFromImages(scans(2), "A")
        val b = repo.createFromImages(scans(1), "B")
        val merged = repo.merge(listOf(a, b), "AB")
        assertEquals(3, doc(merged)!!.pageCount)
        assertEquals("AB", doc(merged)!!.title)
        val copy = repo.duplicate(a)
        assertEquals("A (copy)", doc(copy)!!.title)
        // Source documents are untouched
        assertEquals(2, doc(a)!!.pageCount)
    }

    @Test
    fun idCard_makesSinglePageDocument() = runBlocking {
        val id = repo.createIdCard(scans(2))
        assertEquals(1, doc(id)!!.pageCount)
    }

    @Test
    fun compressedCopy_isSmaller() = runBlocking {
        val id = repo.createFromImages(scans(2))
        val r = repo.compressedPdf(id)
        assertTrue("${r.after} < ${r.before}", r.after < r.before * 0.8)
    }

    @Test
    fun details_reportPagesAndResolution() = runBlocking {
        val id = repo.createFromImages(scans(2))
        val d = repo.details(id)
        assertNotNull(d)
        assertEquals(2, d!!.pages)
        assertEquals("1200 × 1600 px", d.resolution)
    }

    @Test
    fun rename_andFavorite() = runBlocking {
        val id = repo.createFromImages(scans(1))
        repo.rename(id, "  Lease agreement ")
        repo.toggleFavorite(id)
        val d = doc(id)!!
        assertEquals("Lease agreement", d.title)
        assertTrue(d.favorite)
        assertEquals(listOf(id), repo.observeDocuments("lease", true).first().map { it.id })
    }
}
