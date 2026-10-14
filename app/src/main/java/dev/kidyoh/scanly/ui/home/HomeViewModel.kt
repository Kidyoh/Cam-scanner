package dev.kidyoh.scanly.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kidyoh.scanly.ScanlyApp
import dev.kidyoh.scanly.data.DocumentEntity
import dev.kidyoh.scanly.data.Progress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeEvent {
    data class Open(val id: Long) : HomeEvent
    data class Message(val text: String) : HomeEvent
}

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class HomeViewModel(private val app: ScanlyApp) : ViewModel() {
    private val repo = app.repository
    val settings = app.settings.settings

    val query = MutableStateFlow("")
    val favoritesOnly = MutableStateFlow(false)
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    val busy = MutableStateFlow<String?>(null)

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val documents: StateFlow<List<DocumentEntity>?> =
        combine(query.debounce(150), favoritesOnly) { q, f -> q to f }
            .flatMapLatest { (q, f) -> repo.observeDocuments(q, f) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            app.incoming.filterNotNull().collect { share ->
                app.incoming.value = null
                if (share.isPdf) importPdf(share.uris.first()) else importImages(share.uris)
            }
        }
    }

    fun onScanned(uris: List<Uri>) = work("Processing scan…") { p ->
        _events.send(HomeEvent.Open(repo.createFromImages(uris, progress = p)))
    }

    fun importImages(uris: List<Uri>) = work("Importing images…") { p ->
        _events.send(HomeEvent.Open(repo.createFromImages(uris, progress = p)))
    }

    fun importPdf(uri: Uri) = work("Importing PDF…") { p ->
        _events.send(HomeEvent.Open(repo.importPdf(uri, p)))
    }

    fun toggleSelect(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun selectAll() {
        selection.value = documents.value.orEmpty().map { it.id }.toSet()
    }

    fun toggleFavorite(id: Long) = viewModelScope.launch { repo.toggleFavorite(id) }

    fun deleteSelected() = work("Deleting…") {
        val ids = selection.value
        repo.delete(ids)
        clearSelection()
        _events.send(HomeEvent.Message("Deleted ${ids.size} document${if (ids.size == 1) "" else "s"}"))
    }

    /** Merges selected documents in the order they were selected. */
    fun mergeSelected(title: String) = work("Merging…") { p ->
        val ids = selection.value.toList()
        val id = repo.merge(ids, title, p)
        clearSelection()
        _events.send(HomeEvent.Open(id))
    }


    private fun work(initial: String, block: suspend (Progress) -> Unit) = viewModelScope.launch {
        busy.value = initial
        try {
            block { busy.value = it }
        } catch (e: Exception) {
            _events.send(HomeEvent.Message(e.message ?: "Something went wrong"))
        } finally {
            busy.value = null
        }
    }
}
