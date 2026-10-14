package dev.kidyoh.scanly.ui.document

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dev.kidyoh.scanly.ScanlyApp
import dev.kidyoh.scanly.data.PageEntity
import dev.kidyoh.scanly.ui.components.BusyOverlay
import dev.kidyoh.scanly.ui.components.ConfirmDialog
import dev.kidyoh.scanly.ui.components.PasswordDialog
import dev.kidyoh.scanly.ui.components.TextInputDialog
import dev.kidyoh.scanly.util.formatSize
import dev.kidyoh.scanly.util.openPdfExternally
import dev.kidyoh.scanly.util.rememberDocumentScanner
import dev.kidyoh.scanly.util.shareImages
import dev.kidyoh.scanly.util.sharePdf
import dev.kidyoh.scanly.util.shareText
import java.io.File

private enum class PasswordAction { SHARE, SAVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(documentId: Long, onBack: () -> Unit, openDocument: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ScanlyApp
    val vm: DocumentViewModel = viewModel(key = "doc$documentId") { DocumentViewModel(app, documentId) }

    val doc by vm.document.collectAsState()
    val pages by vm.pages.collectAsState()
    val busy by vm.busy.collectAsState()
    val text by vm.extractedText.collectAsState()
    val settings by vm.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var passwordAction by remember { mutableStateOf<PasswordAction?>(null) }
    var pendingPassword by remember { mutableStateOf<String?>(null) }
    var viewerStart by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is DocEvent.Message -> snackbar.showSnackbar(e.text)
                is DocEvent.SharePdf -> context.sharePdf(e.uri, e.title)
                is DocEvent.ShareImages -> context.shareImages(e.uris)
                is DocEvent.OpenExternally -> context.openPdfExternally(e.uri)
                is DocEvent.OpenDocument -> openDocument(e.id)
                DocEvent.Closed -> onBack()
            }
        }
    }

    val addPages = rememberDocumentScanner(settings.pageLimit) { vm.addPages(it) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.exportTo(uri, pendingPassword)
        pendingPassword = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { renaming = true }) {
                        Text(doc?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        doc?.let {
                            Text(
                                "${it.pageCount} page${if (it.pageCount == 1) "" else "s"} · ${formatSize(it.fileSize)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = vm::toggleFavorite) {
                        Icon(
                            if (doc?.favorite == true) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favourite",
                            tint = if (doc?.favorite == true) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            MenuItem("Open in PDF reader", Icons.AutoMirrored.Outlined.OpenInNew) { menuOpen = false; vm.openExternally() }
                            MenuItem("Share with password", Icons.Outlined.Lock) {
                                menuOpen = false; passwordAction = PasswordAction.SHARE
                            }
                            MenuItem("Save with password", Icons.Outlined.Lock) {
                                menuOpen = false; passwordAction = PasswordAction.SAVE
                            }
                            MenuItem("Share pages as images", Icons.Outlined.Image) { menuOpen = false; vm.shareImages() }
                            MenuItem("Duplicate", Icons.Outlined.FileCopy) { menuOpen = false; vm.duplicate() }
                            MenuItem("Rename", Icons.Outlined.Edit) { menuOpen = false; renaming = true }
                            HorizontalDivider()
                            MenuItem("Delete", Icons.Outlined.Delete) { menuOpen = false; confirmDelete = true }
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                BottomAction("Add pages", Icons.Outlined.AddAPhoto, Modifier.weight(1f)) { addPages() }
                BottomAction("Text", Icons.AutoMirrored.Outlined.TextSnippet, Modifier.weight(1f)) { vm.extractText() }
                BottomAction("Save", Icons.Outlined.Download, Modifier.weight(1f)) { saveAs.launch(vm.suggestedFileName()) }
                BottomAction("Share", Icons.Outlined.Share, Modifier.weight(1f)) { vm.share() }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (doc?.searchText?.isNotBlank() == true) {
                item {
                    AssistChip(
                        onClick = vm::extractText,
                        label = { Text("Searchable PDF · text recognised") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.TextSnippet, null) },
                    )
                }
            }
            itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                PageCard(
                    page = page,
                    index = index,
                    count = pages.size,
                    onOpen = { viewerStart = index },
                    onRotate = { vm.rotatePage(page.id) },
                    onUp = { vm.movePage(page.id, -1) },
                    onDown = { vm.movePage(page.id, 1) },
                    onDelete = { vm.deletePage(page.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    BusyOverlay(busy)

    viewerStart?.let { start -> PageViewer(pages, start) { viewerStart = null } }

    text?.let { t ->
        ModalBottomSheet(onDismissRequest = vm::dismissText) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("Recognised text", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Scanned text", t))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("Copy all")
                    }
                    OutlinedButton(onClick = { context.shareText(t) }) {
                        Icon(Icons.Outlined.Share, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("Share")
                    }
                }
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        t,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog("Rename", doc?.title.orEmpty(), onDismiss = { renaming = false }) {
            vm.rename(it); renaming = false
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete document?",
            message = "\"${doc?.title}\" will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; vm.delete() },
        )
    }
    passwordAction?.let { action ->
        PasswordDialog(
            title = if (action == PasswordAction.SHARE) "Share with password" else "Save with password",
            confirmLabel = if (action == PasswordAction.SHARE) "Share" else "Save",
            onDismiss = { passwordAction = null },
        ) { pw ->
            passwordAction = null
            if (action == PasswordAction.SHARE) {
                vm.share(pw)
            } else {
                pendingPassword = pw
                saveAs.launch(vm.suggestedFileName())
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun BottomAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun PageCard(
    page: PageEntity,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onRotate: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        AsyncImage(
            model = File(page.imagePath),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().background(Color.White).clickable(onClick = onOpen),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Page ${index + 1} of $count",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            IconButton(onClick = onRotate) { Icon(Icons.AutoMirrored.Outlined.RotateRight, "Rotate") }
            IconButton(onClick = onUp, enabled = index > 0) { Icon(Icons.Outlined.ArrowUpward, "Move up") }
            IconButton(onClick = onDown, enabled = index < count - 1) { Icon(Icons.Outlined.ArrowDownward, "Move down") }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "Delete page") }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "Remove page ${index + 1}?",
            message = if (count == 1) "This is the only page, so the whole document will be deleted." else "The page will be removed from this document.",
            confirmLabel = "Remove",
            onDismiss = { confirm = false },
            onConfirm = { confirm = false; onDelete() },
        )
    }
}

/** Full-screen swipeable page viewer with pinch-to-zoom and double-tap zoom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageViewer(pages: List<PageEntity>, start: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) { pages.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
                ZoomableImage(File(pages[i].imagePath))
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                Text("${pager.currentPage + 1} / ${pages.size}", color = Color.White)
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f; offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .then(
                if (scale > 1f) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                } else {
                    Modifier.pointerInput(Unit) {
                        // Only intercept multi-touch so single-finger swipes still page through.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1) {
                                    val zoom = event.calculateZoom()
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                },
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
