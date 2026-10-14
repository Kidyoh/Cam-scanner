package dev.kidyoh.scanly.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ElevatedCard
import androidx.compose.ui.graphics.vector.ImageVector
import dev.kidyoh.scanly.data.SortOrder
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.kidyoh.scanly.ScanlyApp
import dev.kidyoh.scanly.data.DocumentEntity
import dev.kidyoh.scanly.ui.components.BusyOverlay
import dev.kidyoh.scanly.ui.components.ConfirmDialog
import dev.kidyoh.scanly.ui.components.TextInputDialog
import dev.kidyoh.scanly.util.PublicStorage
import dev.kidyoh.scanly.util.formatSize
import dev.kidyoh.scanly.util.rememberStorageAccess
import dev.kidyoh.scanly.util.rememberDocumentScanner
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(openDocument: (Long) -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ScanlyApp
    val vm: HomeViewModel = viewModel { HomeViewModel(app) }

    val docs by vm.documents.collectAsState()
    val query by vm.query.collectAsState()
    val favoritesOnly by vm.favoritesOnly.collectAsState()
    val selection by vm.selection.collectAsState()
    val busy by vm.busy.collectAsState()
    val settings by vm.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }
    var qrResult by remember { mutableStateOf<Barcode?>(null) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is HomeEvent.Open -> openDocument(e.id)
                is HomeEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    val scan = rememberDocumentScanner(settings.pageLimit) { vm.onScanned(it) }
    val scanIdCard = rememberDocumentScanner(pageLimit = 2) { vm.onIdCardScanned(it) }
    var sortMenu by remember { mutableStateOf(false) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importPdf(it) }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) {
        if (it.isNotEmpty()) vm.importImages(it)
    }
    val scanQr = {
        GmsBarcodeScanning.getClient(context).startScan()
            .addOnSuccessListener { qrResult = it }
            .addOnFailureListener {
                Toast.makeText(context, it.localizedMessage ?: "QR scanner unavailable", Toast.LENGTH_SHORT).show()
            }
    }

    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    // Android 8–9 only: ask once for storage so auto-save to Downloads/Gallery works.
    val withStorage = rememberStorageAccess()
    LaunchedEffect(Unit) {
        if ((settings.autoSavePdf || settings.autoSaveImages) && PublicStorage.needsPermission(context)) withStorage {}
    }

    Scaffold(
        topBar = {
            AnimatedContent(targetState = selection.isNotEmpty(), label = "topbar") { selecting ->
                if (selecting) {
                    TopAppBar(
                        title = { Text("${selection.size} selected") },
                        navigationIcon = {
                            IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Clear selection") }
                        },
                        actions = {
                            IconButton(onClick = vm::selectAll) { Icon(Icons.Outlined.SelectAll, "Select all") }
                            IconButton(onClick = { mergeDialog = true }, enabled = selection.size >= 2) {
                                Icon(Icons.AutoMirrored.Outlined.CallMerge, "Merge")
                            }
                            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                } else {
                    Column {
                        TopAppBar(
                            title = { Text("Scanly", fontWeight = FontWeight.Bold) },
                            actions = {
                                Box {
                                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                        SortOrder.entries.forEach { order ->
                                            DropdownMenuItem(
                                                text = { Text(order.label) },
                                                leadingIcon = {
                                                    if (settings.sortOrder == order) Icon(Icons.Outlined.Check, null)
                                                },
                                                onClick = { sortMenu = false; vm.setSort(order) },
                                            )
                                        }
                                    }
                                }
                                Box {
                                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Settings") },
                                            leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                            onClick = { menuOpen = false; openSettings() },
                                        )
                                    }
                                }
                            },
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { vm.query.value = it },
                            placeholder = { Text("Search titles and text inside scans") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "Clear") }
                                }
                            },
                            singleLine = true,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = !favoritesOnly,
                                onClick = { vm.favoritesOnly.value = false },
                                label = { Text("All") },
                            )
                            FilterChip(
                                selected = favoritesOnly,
                                onClick = { vm.favoritesOnly.value = true },
                                label = { Text("Favourites") },
                                leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) },
                            )
                        }
                        if (query.isEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(bottom = 8.dp),
                            ) {
                                val tools = listOf(
                                    Tool("ID card", "Front + back on one page", Icons.Outlined.Badge) { scanIdCard() },
                                    Tool("Photos", "Import from gallery", Icons.Outlined.Image) {
                                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    Tool("PDF", "Import & edit a PDF", Icons.Outlined.PictureAsPdf) {
                                        pickPdf.launch(arrayOf("application/pdf"))
                                    },
                                    Tool("QR code", "Scan QR & barcodes", Icons.Outlined.QrCodeScanner) { scanQr() },
                                )
                                rowItems(tools) { ToolCard(it) }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (selection.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = scan,
                    icon = { Icon(Icons.Filled.DocumentScanner, null) },
                    text = { Text("Scan") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = docs
            when {
                list == null -> Unit
                list.isEmpty() && (query.isNotEmpty() || favoritesOnly) -> EmptyState(
                    title = "Nothing found",
                    body = if (query.isNotEmpty()) "No document title or text matches \"$query\"." else "Star a document to see it here.",
                )
                list.isEmpty() -> EmptyState(
                    title = "Scan your first document",
                    body = "Tap Scan. Edges are detected automatically, pages are flattened and cleaned, and text is recognised so every PDF is searchable. PDFs are saved to Downloads/Scanly.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(156.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { doc ->
                        DocumentCard(
                            doc = doc,
                            query = query,
                            selected = doc.id in selection,
                            selecting = selection.isNotEmpty(),
                            onClick = { if (selection.isNotEmpty()) vm.toggleSelect(doc.id) else openDocument(doc.id) },
                            onLongClick = { vm.toggleSelect(doc.id) },
                            onFavorite = { vm.toggleFavorite(doc.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    BusyOverlay(busy)

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${selection.size} document${if (selection.size == 1) "" else "s"}?",
            message = "This can't be undone.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; vm.deleteSelected() },
        )
    }
    if (mergeDialog) {
        TextInputDialog(
            title = "Merge ${selection.size} documents",
            initial = "Merged document",
            confirmLabel = "Merge",
            onDismiss = { mergeDialog = false },
            onConfirm = { mergeDialog = false; vm.mergeSelected(it) },
        )
    }
    qrResult?.let { QrResultDialog(it, onDismiss = { qrResult = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    doc: DocumentEntity,
    query: String,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Card(
        modifier = modifier
            .border(3.dp, borderColor, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            AsyncImage(
                model = doc.thumbnailPath?.let { File(it) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "${doc.pageCount} pg",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (selecting) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp)
                        .background(Color.White, CircleShape),
                )
            } else {
                IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(
                        if (doc.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favourite",
                        tint = if (doc.favorite) Color(0xFFFFC107) else Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(4.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                doc.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.updatedAt)) + " · " + formatSize(doc.fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            matchSnippet(doc, query)?.let { snippet ->
                Spacer(Modifier.height(6.dp))
                val idx = snippet.indexOf(query, ignoreCase = true)
                Text(
                    buildAnnotatedString {
                        append(snippet.substring(0, idx))
                        withStyle(
                            SpanStyle(
                                background = MaterialTheme.colorScheme.primaryContainer,
                                fontWeight = FontWeight.Bold,
                            ),
                        ) { append(snippet.substring(idx, idx + query.length)) }
                        append(snippet.substring(idx + query.length))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A short excerpt of recognised text around the search match, when the title doesn't match. */
private fun matchSnippet(doc: DocumentEntity, query: String): String? {
    val q = query.trim()
    if (q.length < 2 || doc.title.contains(q, ignoreCase = true)) return null
    val text = doc.searchText.replace('\n', ' ')
    val i = text.indexOf(q, ignoreCase = true)
    if (i < 0) return null
    val start = (i - 24).coerceAtLeast(0)
    val end = (i + q.length + 40).coerceAtMost(text.length)
    return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.DocumentScanner,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .padding(22.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QrResultDialog(barcode: Barcode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val value = barcode.rawValue.orEmpty()
    val url = barcode.url?.url ?: value.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val kind = when (barcode.valueType) {
        Barcode.TYPE_URL -> "Link"
        Barcode.TYPE_WIFI -> "Wi-Fi network"
        Barcode.TYPE_CONTACT_INFO -> "Contact"
        Barcode.TYPE_EMAIL -> "Email"
        Barcode.TYPE_PHONE -> "Phone number"
        Barcode.TYPE_SMS -> "SMS"
        Barcode.TYPE_GEO -> "Location"
        Barcode.TYPE_CALENDAR_EVENT -> "Calendar event"
        Barcode.TYPE_PRODUCT, Barcode.TYPE_ISBN -> "Product code"
        else -> "Text"
    }
    val wifi = barcode.wifi
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wifi != null) {
                    Text("Network: ${wifi.ssid.orEmpty()}")
                    Text("Password: ${wifi.password.orEmpty()}")
                } else {
                    Text(value)
                }
            }
        },
        confirmButton = {
            if (url != null) {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    onDismiss()
                }) { Text("Open") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val copy = wifi?.password ?: value
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(kind, copy))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text(if (wifi != null) "Copy password" else "Copy") }
        },
    )
}

private class Tool(val title: String, val subtitle: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun ToolCard(tool: Tool) {
    ElevatedCard(onClick = tool.onClick) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                tool.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(7.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column {
                Text(tool.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    tool.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
