package dev.kidyoh.scanly.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import dev.kidyoh.scanly.pdf.Composer
import dev.kidyoh.scanly.pdf.Filters
import dev.kidyoh.scanly.pdf.ImageUtils
import dev.kidyoh.scanly.pdf.OcrEngine
import dev.kidyoh.scanly.pdf.OcrResult
import dev.kidyoh.scanly.pdf.PageFilter
import dev.kidyoh.scanly.pdf.PdfBuilder
import dev.kidyoh.scanly.pdf.PdfImporter
import dev.kidyoh.scanly.pdf.PdfPageInput
import dev.kidyoh.scanly.util.PublicStorage
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

data class DocumentDetails(
    val pages: Int,
    val fileSize: Long,
    val words: Int,
    val characters: Int,
    val resolution: String?,
    val filters: Set<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val publicCopy: String?,
)

data class CompressResult(val uri: Uri, val before: Long, val after: Long)

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val settingsStore: SettingsStore,
    private val ocr: OcrEngine,
) {
    private val root = File(context.filesDir, "documents")
    private val shareDir = File(context.cacheDir, "shared")
    private val signatureFile = File(context.filesDir, "signature/signature.png")
    private val writeLock = Mutex()
    private val settings get() = settingsStore.settings.value

    fun observeDocuments(query: String, favoritesOnly: Boolean): Flow<List<DocumentEntity>> =
        dao.observeDocuments(query.trim(), favoritesOnly)

    fun observeDocument(id: Long) = dao.observeDocument(id)
    fun observePages(id: Long) = dao.observePages(id)

    private fun docDir(id: Long) = File(root, id.toString())
    private fun pagesDir(id: Long) = File(docDir(id), "pages")
    private fun newPageFile(id: Long, prefix: String = "p") = File(pagesDir(id), "${prefix}_${System.nanoTime()}.jpg")
    private val jpegQuality get() = settings.quality.jpegQuality

    // ---------------------------------------------------------------- creation

    /** Creates a new document from scanned or picked images. Returns the new document id. */
    suspend fun createFromImages(uris: List<Uri>, title: String? = null, progress: Progress = {}): Long =
        io {
            newDocument(title) { id ->
                val pages = ingest(id, uris, startPosition = 0, progress)
                if (title == null) autoTitle(id)
                rebuild(id, progress)
                autoSaveImages(id, pages, progress)
            }
        }

    /** Imports an existing PDF: pages are rasterized so they can be edited and OCR'd. */
    suspend fun importPdf(uri: Uri, progress: Progress = {}): Long = io {
        progress("Reading PDF…")
        val staging = File(context.cacheDir, "import_${System.nanoTime()}")
        try {
            val files = PdfImporter.render(context, uri, staging, settings.quality.maxSide.coerceAtMost(3000), jpegQuality)
            require(files.isNotEmpty()) { "That PDF has no pages" }
            newDocument(displayName(uri)?.removeSuffix(".pdf")) { id ->
                ingest(id, files.map { Uri.fromFile(it) }, 0, progress)
                rebuild(id, progress)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Places the front and back of an ID card on a single A4 page at real size. */
    suspend fun createIdCard(uris: List<Uri>, progress: Progress = {}): Long = io {
        progress("Laying out ID card…")
        val staging = File(context.cacheDir, "id_${System.nanoTime()}").apply { mkdirs() }
        try {
            val sides = uris.take(2).mapIndexedNotNull { i, uri ->
                File(staging, "side$i.jpg").takeIf { ImageUtils.normalize(context, uri, it, 2400, 0.95f) }
            }
            require(sides.isNotEmpty()) { "Couldn't read the card images" }
            val sheet = File(staging, "sheet.jpg")
            Composer.idCard(sides, sheet, 0.92f)
            newDocument("ID card " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) { id ->
                ingest(id, listOf(Uri.fromFile(sheet)), 0, progress)
                rebuild(id, progress)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun addPages(docId: Long, uris: List<Uri>, progress: Progress = {}) = io {
        val start = dao.getPages(docId).size
        val pages = ingest(docId, uris, start, progress)
        rebuild(docId, progress)
        autoSaveImages(docId, pages, progress)
    }

    /**
     * Inserts an empty document, runs [fill], and removes the document again if anything
     * fails so no broken, page-less entries are left in the library.
     */
    private suspend fun newDocument(title: String?, fill: suspend (Long) -> Unit): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertDocument(
            DocumentEntity(title = title?.takeIf { it.isNotBlank() } ?: defaultTitle(), createdAt = now, updatedAt = now, pdfPath = ""),
        )
        pagesDir(id).mkdirs()
        try {
            fill(id)
        } catch (e: Throwable) {
            dao.getDocument(id)?.let { deleteNow(it) }
            throw e
        }
        return id
    }

    private suspend fun ingest(docId: Long, uris: List<Uri>, startPosition: Int, progress: Progress): List<PageEntity> {
        val q = settings.quality
        var position = startPosition
        val pages = uris.mapIndexedNotNull { i, uri ->
            progress("Saving page ${i + 1} of ${uris.size}…")
            val dest = newPageFile(docId)
            if (!ImageUtils.normalize(context, uri, dest, q.maxSide, q.jpegQuality)) return@mapIndexedNotNull null
            val text = if (settings.ocrEnabled) {
                progress("Reading text on page ${i + 1} of ${uris.size}…")
                ocr.recognize(dest)?.toJson()
            } else {
                null
            }
            PageEntity(documentId = docId, position = position++, imagePath = dest.absolutePath, ocrJson = text)
        }
        require(pages.isNotEmpty()) { "Couldn't read any of the scanned images" }
        dao.insertPages(pages)
        return pages
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
            PdfBuilder.build(pages.map { it.toPdfInput() }, pdf, settings.pageSize, jpegQuality)
            doc.thumbnailPath?.let { File(it).delete() }
            val thumb = File(docDir(docId), "thumb_${System.currentTimeMillis()}.jpg")
            ImageUtils.makeThumbnail(File(pages.first().imagePath), thumb)
            val searchText = pages.mapNotNull { OcrResult.fromJson(it.ocrJson)?.text }.joinToString("\n")
            var updated = doc.copy(
                pdfPath = pdf.absolutePath,
                thumbnailPath = thumb.absolutePath,
                pageCount = pages.size,
                fileSize = pdf.length(),
                searchText = searchText,
                updatedAt = System.currentTimeMillis(),
            )
            if (settings.autoSavePdf && !PublicStorage.needsPermission(context)) {
                progress("Saving to ${PublicStorage.pdfFolder()}…")
                runCatching {
                    PublicStorage.savePdf(context, pdf, safeFileName(updated.title), updated.publicPdfUri?.let(Uri::parse))
                }.onSuccess { updated = updated.copy(publicPdfUri = it.toString()) }
            }
            dao.updateDocument(updated)
        }
    }

    private fun PageEntity.toPdfInput() = PdfPageInput(File(imagePath), OcrResult.fromJson(ocrJson))

    // ------------------------------------------------------------ page editing

    suspend fun rotatePage(pageId: Long, degrees: Float = 90f) = io {
        val page = dao.getPage(pageId) ?: return@io
        val dest = newPageFile(page.documentId)
        ImageUtils.rotateFile(File(page.imagePath), dest, degrees)
        val original = page.originalPath?.let { path ->
            newPageFile(page.documentId, "o").also { ImageUtils.rotateFile(File(path), it, degrees); File(path).delete() }
        }
        val text = if (page.ocrJson != null || settings.ocrEnabled) ocr.recognize(dest)?.toJson() else null
        dao.updatePages(listOf(page.copy(imagePath = dest.absolutePath, originalPath = original?.absolutePath, ocrJson = text)))
        File(page.imagePath).delete()
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
        page.originalPath?.let { File(it).delete() }
        dao.replacePageOrder(dao.getPages(page.documentId))
        rebuild(page.documentId)
    }

    /**
     * Applies [filter] to the given pages. Filters always start from the untouched original,
     * so switching between them never degrades the image.
     */
    suspend fun applyFilter(pageIds: List<Long>, filter: PageFilter, progress: Progress = {}) = io {
        var docId: Long? = null
        pageIds.forEachIndexed { i, id ->
            val page = dao.getPage(id) ?: return@forEachIndexed
            docId = page.documentId
            if (pageIds.size > 1) progress("Applying ${filter.label} to page ${i + 1} of ${pageIds.size}…")
            val original = page.originalPath?.let(::File)
                ?: newPageFile(page.documentId, "o").also { File(page.imagePath).copyTo(it) }
            val dest = newPageFile(page.documentId)
            if (filter == PageFilter.ORIGINAL) original.copyTo(dest) else Filters.applyToFile(original, dest, filter, jpegQuality)
            dao.updatePages(listOf(page.copy(imagePath = dest.absolutePath, originalPath = original.absolutePath, filter = filter.name)))
            File(page.imagePath).delete()
        }
        docId?.let { rebuild(it, progress) }
    }

    suspend fun filterPreviews(pageId: Long): Map<PageFilter, Bitmap> = withContext(Dispatchers.Default) {
        val page = dao.getPage(pageId) ?: return@withContext emptyMap()
        Filters.previews(File(page.originalPath ?: page.imagePath))
    }

    // --------------------------------------------------------------- signature

    fun savedSignature(): Bitmap? = signatureFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    suspend fun saveSignature(bitmap: Bitmap) = io {
        signatureFile.parentFile?.mkdirs()
        signatureFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Burns the signature into the page (and its original, so it survives filter changes). */
    suspend fun stampSignature(pageId: Long, signature: Bitmap, left: Float, top: Float, width: Float) = io {
        val page = dao.getPage(pageId) ?: return@io
        val dest = newPageFile(page.documentId)
        Composer.stamp(File(page.imagePath), dest, signature, left, top, width, 0.92f)
        val original = page.originalPath?.let { path ->
            newPageFile(page.documentId, "o").also {
                Composer.stamp(File(path), it, signature, left, top, width, 0.92f)
                File(path).delete()
            }
        }
        dao.updatePages(listOf(page.copy(imagePath = dest.absolutePath, originalPath = original?.absolutePath)))
        File(page.imagePath).delete()
        rebuild(page.documentId)
    }

    // ------------------------------------------------------- document actions

    suspend fun rename(docId: Long, title: String) = io {
        val doc = dao.getDocument(docId) ?: return@io
        val newTitle = title.trim().ifBlank { doc.title }
        dao.updateDocument(doc.copy(title = newTitle))
        // Keep the Downloads copy's file name in sync.
        if (doc.publicPdfUri != null && newTitle != doc.title) rebuild(docId)
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
        require(docs.isNotEmpty()) { "Nothing to merge" }
        val newTitle = title ?: if (docs.size == 1) "${docs[0].title} (copy)" else "Merged · ${docs.first().title}"
        newDocument(newTitle) { id ->
            var pos = 0
            val pages = docs.flatMap { dao.getPages(it.id) }.map { p ->
                val dest = newPageFile(id)
                File(p.imagePath).copyTo(dest)
                val original = p.originalPath?.let { o -> newPageFile(id, "o").also { File(o).copyTo(it) } }
                PageEntity(
                    documentId = id,
                    position = pos++,
                    imagePath = dest.absolutePath,
                    ocrJson = p.ocrJson,
                    originalPath = original?.absolutePath,
                    filter = p.filter,
                )
            }
            dao.insertPages(pages)
            rebuild(id, progress)
        }
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

    suspend fun details(docId: Long): DocumentDetails? = io {
        val doc = dao.getDocument(docId) ?: return@io null
        val pages = dao.getPages(docId)
        val resolution = pages.firstOrNull()?.let { p ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(p.imagePath, o)
            "${o.outWidth} × ${o.outHeight} px"
        }
        DocumentDetails(
            pages = pages.size,
            fileSize = doc.fileSize,
            words = doc.searchText.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) },
            characters = doc.searchText.count { !it.isWhitespace() },
            resolution = resolution,
            filters = pages.map { PageFilter.of(it.filter).label }.toSet(),
            createdAt = doc.createdAt,
            updatedAt = doc.updatedAt,
            publicCopy = doc.publicPdfUri?.let { "${PublicStorage.pdfFolder()}/${safeFileName(doc.title)}.pdf" },
        )
    }

    // ------------------------------------------------------- sharing & export

    /** Saves page images to the Gallery (Pictures/Scanly). Returns how many were saved. */
    suspend fun saveImagesToGallery(docId: Long, pageIds: List<Long>? = null, progress: Progress = {}): Int = io {
        val doc = requireNotNull(dao.getDocument(docId))
        val pages = dao.getPages(docId)
        val selected = pages.withIndex().filter { pageIds == null || it.value.id in pageIds }
        selected.forEachIndexed { n, (i, page) ->
            progress("Saving image ${n + 1} of ${selected.size}…")
            val suffix = if (pages.size > 1) " - page ${i + 1}" else ""
            PublicStorage.saveImage(context, File(page.imagePath), safeFileName(doc.title) + suffix)
        }
        selected.size
    }

    private suspend fun autoSaveImages(docId: Long, newPages: List<PageEntity>, progress: Progress) {
        if (!settings.autoSaveImages || PublicStorage.needsPermission(context)) return
        val ids = dao.getPages(docId).filter { p -> newPages.any { it.imagePath == p.imagePath } }.map { it.id }
        runCatching { saveImagesToGallery(docId, ids, progress) }
    }

    /** Saves (or refreshes) the PDF in Download/Scanly and returns the folder name. */
    suspend fun savePdfToDownloads(docId: Long): String = io {
        val doc = requireNotNull(dao.getDocument(docId))
        val uri = PublicStorage.savePdf(context, File(doc.pdfPath), safeFileName(doc.title), doc.publicPdfUri?.let(Uri::parse))
        dao.updateDocument(doc.copy(publicPdfUri = uri.toString()))
        PublicStorage.pdfFolder()
    }

    /** Writes the PDF (optionally password-protected) to a user-picked location. */
    suspend fun exportTo(docId: Long, target: Uri, password: String? = null) = io {
        val doc = requireNotNull(dao.getDocument(docId))
        val src = preparedPdf(doc, password)
        writeTo(target) { out -> src.inputStream().use { it.copyTo(out) } }
    }

    suspend fun writeText(target: Uri, text: String) = io {
        writeTo(target) { it.write(text.toByteArray()) }
    }

    /** Returns a content:// URI suitable for ACTION_SEND. */
    suspend fun shareablePdf(docId: Long, password: String? = null): Uri = io {
        val doc = requireNotNull(dao.getDocument(docId))
        FileProvider.getUriForFile(context, authority, preparedPdf(doc, password))
    }

    /** Builds a smaller copy (downscaled, stronger JPEG compression) for email and messaging. */
    suspend fun compressedPdf(docId: Long, progress: Progress = {}): CompressResult = io {
        val doc = requireNotNull(dao.getDocument(docId))
        progress("Compressing…")
        val out = freshShareFile(doc)
        PdfBuilder.build(dao.getPages(docId).map { it.toPdfInput() }, out, settings.pageSize, 0.55f, 1400)
        // Already-small scans can't shrink further; never hand back a bigger file.
        if (out.length() >= doc.fileSize) File(doc.pdfPath).copyTo(out, overwrite = true)
        CompressResult(FileProvider.getUriForFile(context, authority, out), doc.fileSize, out.length())
    }

    suspend fun shareableImages(docId: Long): List<Uri> = io {
        dao.getPages(docId).map { FileProvider.getUriForFile(context, authority, File(it.imagePath)) }
    }

    suspend fun pdfFile(docId: Long): File = io { File(requireNotNull(dao.getDocument(docId)).pdfPath) }

    private fun preparedPdf(doc: DocumentEntity, password: String?): File {
        val out = freshShareFile(doc)
        if (password.isNullOrEmpty()) {
            File(doc.pdfPath).copyTo(out, overwrite = true)
        } else {
            PdfBuilder.encrypt(File(doc.pdfPath), out, password)
        }
        return out
    }

    private fun freshShareFile(doc: DocumentEntity): File {
        shareDir.mkdirs()
        shareDir.listFiles()?.forEach { it.delete() }
        return File(shareDir, safeFileName(doc.title) + ".pdf")
    }

    private fun writeTo(target: Uri, write: (java.io.OutputStream) -> Unit) {
        context.contentResolver.openOutputStream(target, "wt")?.use(write) ?: error("Couldn't write to that location")
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
