package dev.kidyoh.scanly.ui.document

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kidyoh.scanly.ScanlyApp
import dev.kidyoh.scanly.data.DocumentDetails
import dev.kidyoh.scanly.data.DocumentEntity
import dev.kidyoh.scanly.data.PageEntity
import dev.kidyoh.scanly.data.Progress
import dev.kidyoh.scanly.pdf.PageFilter
import dev.kidyoh.scanly.util.PublicStorage
import dev.kidyoh.scanly.util.formatSize
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface DocEvent {
    data class Message(val text: String) : DocEvent
    data class SharePdf(val uri: Uri, val title: String) : DocEvent
    data class ShareImages(val uris: List<Uri>) : DocEvent
    data class OpenExternally(val uri: Uri) : DocEvent
    data class Print(val file: File, val title: String) : DocEvent
    data class OpenDocument(val id: Long) : DocEvent
    data object Closed : DocEvent
}

class DocumentViewModel(app: ScanlyApp, private val id: Long) : ViewModel() {
    private val repo = app.repository
    val settings = app.settings.settings

    val document: StateFlow<DocumentEntity?> =
        repo.observeDocument(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pages: StateFlow<List<PageEntity>> =
        repo.observePages(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val busy = MutableStateFlow<String?>(null)
    val extractedText = MutableStateFlow<String?>(null)
    val details = MutableStateFlow<DocumentDetails?>(null)

    private val _events = Channel<DocEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val title get() = document.value?.title ?: "Scan"

    fun rename(title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun toggleFavorite() = viewModelScope.launch { repo.toggleFavorite(id) }

    fun delete() = work("Deleting…") {
        repo.delete(listOf(id))
        _events.send(DocEvent.Closed)
    }

    // ----------------------------------------------------------------- pages

    fun addPages(uris: List<Uri>) = work("Adding pages…") { p -> repo.addPages(id, uris, p) }
    fun rotatePage(pageId: Long) = work("Rotating…") { repo.rotatePage(pageId) }
    fun movePage(pageId: Long, delta: Int) = work("Reordering…") { repo.movePage(pageId, delta) }

    fun deletePage(pageId: Long) = work("Removing page…") {
        val last = pages.value.size <= 1
        repo.deletePage(pageId)
        if (last) _events.send(DocEvent.Closed)
    }

    fun applyFilter(pageId: Long, filter: PageFilter, allPages: Boolean) = work("Applying ${filter.label}…") { p ->
        val ids = if (allPages) pages.value.map { it.id } else listOf(pageId)
        repo.applyFilter(ids, filter, p)
    }

    suspend fun filterPreviews(pageId: Long): Map<PageFilter, Bitmap> = repo.filterPreviews(pageId)

    fun savedSignature(): Bitmap? = repo.savedSignature()

    fun sign(pageId: Long, signature: Bitmap, remember: Boolean, left: Float, top: Float, width: Float) =
        work("Signing…") {
            if (remember) repo.saveSignature(signature)
            repo.stampSignature(pageId, signature, left, top, width)
            _events.send(DocEvent.Message("Signed"))
        }

    // ------------------------------------------------------------------ text

    fun extractText() = work("Reading text…") { p ->
        extractedText.value = repo.extractText(id, p).ifBlank { "No text was found in this document." }
    }

    fun dismissText() {
        extractedText.value = null
    }

    fun saveText(target: Uri, text: String) = work("Saving…") {
        repo.writeText(target, text)
        _events.send(DocEvent.Message("Saved text file"))
    }

    fun showDetails() = viewModelScope.launch { details.value = repo.details(id) }
    fun dismissDetails() {
        details.value = null
    }

    // ---------------------------------------------------------------- saving

    fun saveToDownloads() = work("Saving PDF…") {
        val folder = repo.savePdfToDownloads(id)
        _events.send(DocEvent.Message("Saved to $folder"))
    }

    fun saveToGallery(pageIds: List<Long>? = null) = work("Saving images…") { p ->
        val n = repo.saveImagesToGallery(id, pageIds, p)
        _events.send(DocEvent.Message("Saved $n image${if (n == 1) "" else "s"} to ${PublicStorage.imageFolder()}"))
    }

    fun exportTo(target: Uri, password: String?) = work("Saving…") {
        repo.exportTo(id, target, password)
        _events.send(DocEvent.Message(if (password != null) "Saved password-protected PDF" else "Saved PDF"))
    }

    // --------------------------------------------------------------- sharing

    fun share(password: String? = null) = work(if (password != null) "Encrypting…" else "Preparing…") {
        _events.send(DocEvent.SharePdf(repo.shareablePdf(id, password), title))
    }

    fun shareCompressed() = work("Compressing…") { p ->
        val r = repo.compressedPdf(id, p)
        if (r.after < r.before) {
            _events.send(DocEvent.Message("Compressed ${formatSize(r.before)} → ${formatSize(r.after)}"))
        } else {
            _events.send(DocEvent.Message("Already as small as it gets (${formatSize(r.after)})"))
        }
        _events.send(DocEvent.SharePdf(r.uri, title))
    }

    fun shareImages() = work("Preparing…") { _events.send(DocEvent.ShareImages(repo.shareableImages(id))) }

    fun openExternally() = work("Preparing…") { _events.send(DocEvent.OpenExternally(repo.shareablePdf(id))) }

    fun print() = work("Preparing…") { _events.send(DocEvent.Print(repo.pdfFile(id), title)) }

    fun duplicate() = work("Duplicating…") { _events.send(DocEvent.OpenDocument(repo.duplicate(id))) }

    fun suggestedFileName() = document.value?.let { repo.suggestedFileName(it) } ?: "Scan.pdf"

    private fun work(initial: String, block: suspend (Progress) -> Unit) = viewModelScope.launch {
        busy.value = initial
        try {
            block { busy.value = it }
        } catch (e: Exception) {
            _events.send(DocEvent.Message(e.message ?: "Something went wrong"))
        } finally {
            busy.value = null
        }
    }
}
