package dev.kidyoh.scanly.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.kidyoh.scanly.pdf.ImageUtils
import dev.kidyoh.scanly.pdf.OcrEngine
import dev.kidyoh.scanly.pdf.OcrResult
import dev.kidyoh.scanly.pdf.PdfBuilder
import dev.kidyoh.scanly.pdf.PdfImporter
import dev.kidyoh.scanly.pdf.PdfPageInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias Progress = (String) -> Unit

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val settingsStore: SettingsStore,
    private val ocr: OcrEngine,
) {
    private val root = File(context.filesDir, "documents")
    private val shareDir = File(context.cacheDir, "shared")
    private val writeLock = Mutex()
    private val settings get() = settingsStore.settings.value

    fun observeDocuments(query: String, favoritesOnly: Boolean): Flow<List<DocumentEntity>> =
        dao.observeDocuments(query.trim(), favoritesOnly)

    fun observeDocument(id: Long) = dao.observeDocument(id)
    fun observePages(id: Long) = dao.observePages(id)

    private fun docDir(id: Long) = File(root, id.toString())
    private fun pagesDir(id: Long) = File(docDir(id), "pages")
    private fun newPageFile(id: Long) = File(pagesDir(id), "p_${System.nanoTime()}.jpg")

    // ---------------------------------------------------------------- creation

    /** Creates a new document from scanned or picked images. Returns the new document id. */
    suspend fun createFromImages(uris: List<Uri>, title: String? = null, progress: Progress = {}): Long =
        io {
            val id = newDocument(title)
            ingest(id, uris, startPosition = 0, progress)
            if (title == null) autoTitle(id)
            rebuild(id, progress)
            id
        }

    /** Imports an existing PDF: pages are rasterized so they can be edited and OCR'd. */
    suspend fun importPdf(uri: Uri, progress: Progress = {}): Long = io {
        progress("Reading PDF…")
        val staging = File(context.cacheDir, "import_${System.nanoTime()}")
        try {
            val files = PdfImporter.render(context, uri, staging, settings.quality.maxSide, settings.quality.jpegQuality)
            require(files.isNotEmpty()) { "That PDF has no pages" }
            val id = newDocument(displayName(uri)?.removeSuffix(".pdf"))
            ingest(id, files.map { Uri.fromFile(it) }, 0, progress)
            rebuild(id, progress)
            id
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun addPages(docId: Long, uris: List<Uri>, progress: Progress = {}) = io {
        val start = dao.getPages(docId).size
        ingest(docId, uris, start, progress)
        rebuild(docId, progress)
    }

    private suspend fun newDocument(title: String?): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertDocument(
            DocumentEntity(title = title ?: defaultTitle(), createdAt = now, updatedAt = now, pdfPath = ""),
        )
        pagesDir(id).mkdirs()
        return id
    }

    private suspend fun ingest(docId: Long, uris: List<Uri>, startPosition: Int, progress: Progress) {
        val q = settings.quality
        val pages = uris.mapIndexedNotNull { i, uri ->
            progress("Enhancing page ${i + 1} of ${uris.size}…")
            val dest = newPageFile(docId)
            if (!ImageUtils.normalize(context, uri, dest, q.maxSide, q.jpegQuality)) return@mapIndexedNotNull null
            val text = if (settings.ocrEnabled) {
                progress("Reading text on page ${i + 1} of ${uris.size}…")
                ocr.recognize(dest)?.toJson()
            } else {
                null
            }
            PageEntity(documentId = docId, position = startPosition + i, imagePath = dest.absolutePath, ocrJson = text)
        }
        require(pages.isNotEmpty()) { "Couldn't read any of the images" }
        dao.insertPages(pages)
    }

    /** Names the document after its first meaningful line of text, like a human would. */
    private suspend fun autoTitle(docId: Long) {
        val first = dao.getPages(docId).firstOrNull() ?: return
        val line = OcrResult.fromJson(first.ocrJson)?.lines
            ?.map { it.text.trim() }
            ?.firstOrNull { l -> l.count { it.isLetter() } >= 4 && l.length in 4..60 }
            ?: return
        val doc = dao.getDocument(docId) ?: return
        dao.updateDocument(doc.copy(title = line.take(40).trim()))
    }

    // ------------------------------------------------------------ PDF building

    /** Regenerates the PDF and thumbnail from the current pages. */
    suspend fun rebuild(docId: Long, progress: Progress = {}) = writeLock.withLock {
        io {
            val doc = dao.getDocument(docId) ?: return@io
            val pages = dao.getPages(docId)
            if (pages.isEmpty()) {
                deleteNow(doc)
                return@io
            }
            progress("Building PDF…")
            val pdf = File(docDir(docId), "document.pdf")
            PdfBuilder.build(
                pages.map { PdfPageInput(File(it.imagePath), OcrResult.fromJson(it.ocrJson)) },
                pdf,
                settings.pageSize,
                settings.quality.jpegQuality,
            )
            doc.thumbnailPath?.let { File(it).delete() }
            val thumb = File(docDir(docId), "thumb_${System.currentTimeMillis()}.jpg")
            ImageUtils.makeThumbnail(File(pages.first().imagePath), thumb)
            val searchText = pages.mapNotNull { OcrResult.fromJson(it.ocrJson)?.text }.joinToString("\n")
            dao.updateDocument(
                doc.copy(
                    pdfPath = pdf.absolutePath,
                    thumbnailPath = thumb.absolutePath,
                    pageCount = pages.size,
                    fileSize = pdf.length(),
                    searchText = searchText,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    // ------------------------------------------------------------ page editing

    suspend fun rotatePage(pageId: Long, degrees: Float = 90f) = io {
        val page = dao.getPage(pageId) ?: return@io
        val src = File(page.imagePath)
        val dest = newPageFile(page.documentId)
        ImageUtils.rotateFile(src, dest, degrees)
        val text = if (page.ocrJson != null || settings.ocrEnabled) ocr.recognize(dest)?.toJson() else null
        dao.updatePages(listOf(page.copy(imagePath = dest.absolutePath, ocrJson = text)))
        src.delete()
        rebuild(page.documentId)
    }

    suspend fun movePage(pageId: Long, delta: Int) = io {
        val page = dao.getPage(pageId) ?: return@io
        val pages = dao.getPages(page.documentId).toMutableList()
        val from = pages.indexOfFirst { it.id == pageId }
        val to = (from + delta).coerceIn(0, pages.lastIndex)
        if (from == to) return@io
        pages.add(to, pages.removeAt(from))
        dao.replacePageOrder(pages)
        rebuild(page.documentId)
    }

    suspend fun deletePage(pageId: Long) = io {
        val page = dao.getPage(pageId) ?: return@io
        dao.deletePage(page)
        File(page.imagePath).delete()
        dao.replacePageOrder(dao.getPages(page.documentId))
        rebuild(page.documentId)
    }

    // ------------------------------------------------------- document actions

    suspend fun rename(docId: Long, title: String) = io {
        val doc = dao.getDocument(docId) ?: return@io
        dao.updateDocument(doc.copy(title = title.trim().ifBlank { doc.title }))
    }

    suspend fun toggleFavorite(docId: Long) = io {
        val doc = dao.getDocument(docId) ?: return@io
        dao.updateDocument(doc.copy(favorite = !doc.favorite))
    }

    suspend fun delete(docIds: Collection<Long>) = io {
        docIds.forEach { id -> dao.getDocument(id)?.let { deleteNow(it) } }
    }

    private suspend fun deleteNow(doc: DocumentEntity) {
        dao.deleteDocument(doc)
        docDir(doc.id).deleteRecursively()
    }

    suspend fun duplicate(docId: Long): Long = merge(listOf(docId), null)

    /** Combines several documents (in the given order) into a brand new one. */
    suspend fun merge(docIds: List<Long>, title: String?, progress: Progress = {}): Long = io {
        progress("Merging documents…")
        val docs = docIds.mapNotNull { dao.getDocument(it) }
        val newTitle = title ?: if (docs.size == 1) "${docs[0].title} (copy)" else "Merged · ${docs.first().title}"
        val id = newDocument(newTitle)
        var pos = 0
        val pages = docs.flatMap { dao.getPages(it.id) }.map { p ->
            val dest = newPageFile(id)
            File(p.imagePath).copyTo(dest)
            PageEntity(documentId = id, position = pos++, imagePath = dest.absolutePath, ocrJson = p.ocrJson)
        }
        dao.insertPages(pages)
        rebuild(id, progress)
        id
    }

    /** Runs OCR on any pages that don't have text yet and returns the document's full text. */
    suspend fun extractText(docId: Long, progress: Progress = {}): String = io {
        val pages = dao.getPages(docId)
        val missing = pages.filter { it.ocrJson == null }
        if (missing.isNotEmpty()) {
            val updated = missing.mapIndexed { i, p ->
                progress("Reading text on page ${i + 1} of ${missing.size}…")
                p.copy(ocrJson = ocr.recognize(File(p.imagePath))?.toJson())
            }
            dao.updatePages(updated)
            rebuild(docId, progress)
        }
        dao.getPages(docId).mapIndexed { i, p ->
            val text = OcrResult.fromJson(p.ocrJson)?.text.orEmpty()
            if (pages.size > 1) "— Page ${i + 1} —\n$text" else text
        }.joinToString("\n\n").trim()
    }

    // ------------------------------------------------------- sharing & export

    /** Writes the PDF (optionally password-protected) to a user-picked location. */
    suspend fun exportTo(docId: Long, target: Uri, password: String? = null) = io {
        val doc = requireNotNull(dao.getDocument(docId))
        val src = preparedPdf(doc, password)
        context.contentResolver.openOutputStream(target, "wt")?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: error("Couldn't write to that location")
    }

    /** Returns a content:// URI suitable for ACTION_SEND. */
    suspend fun shareablePdf(docId: Long, password: String? = null): Uri = io {
        val doc = requireNotNull(dao.getDocument(docId))
        FileProvider.getUriForFile(context, authority, preparedPdf(doc, password))
    }

    suspend fun shareableImages(docId: Long): List<Uri> = io {
        dao.getPages(docId).map { FileProvider.getUriForFile(context, authority, File(it.imagePath)) }
    }

    private fun preparedPdf(doc: DocumentEntity, password: String?): File {
        shareDir.mkdirs()
        shareDir.listFiles()?.forEach { it.delete() }
        val out = File(shareDir, safeFileName(doc.title) + ".pdf")
        if (password.isNullOrEmpty()) {
            File(doc.pdfPath).copyTo(out, overwrite = true)
        } else {
            PdfBuilder.encrypt(File(doc.pdfPath), out, password)
        }
        return out
    }

    fun suggestedFileName(doc: DocumentEntity) = safeFileName(doc.title) + ".pdf"

    // ---------------------------------------------------------------- helpers

    private val authority get() = "${context.packageName}.files"

    private fun safeFileName(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().ifBlank { "Scan" }.take(80)

    private fun defaultTitle() =
        "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
