package dev.kidyoh.scanly.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.kidyoh.scanly.ScanlyApp
import dev.kidyoh.scanly.data.PageSize
import dev.kidyoh.scanly.data.Quality
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = (LocalContext.current.applicationContext as ScanlyApp).settings
    val s by store.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("PDF page size")
            PageSize.entries.forEach { size ->
                ListItem(
                    headlineContent = { Text(size.label) },
                    leadingContent = { RadioButton(selected = s.pageSize == size, onClick = null) },
                    modifier = Modifier.selectable(selected = s.pageSize == size) {
                        store.update { it.copy(pageSize = size) }
                    },
                )
            }
            HorizontalDivider()

            Section("Image quality")
            Quality.entries.forEach { q ->
                ListItem(
                    headlineContent = { Text(q.label) },
                    supportingContent = { Text("Up to ${q.maxSide}px · JPEG ${(q.jpegQuality * 100).roundToInt()}%") },
                    leadingContent = { RadioButton(selected = s.quality == q, onClick = null) },
                    modifier = Modifier.selectable(selected = s.quality == q) {
                        store.update { it.copy(quality = q) }
                    },
                )
            }
            HorizontalDivider()

            Section("Text recognition")
            ListItem(
                headlineContent = { Text("Make PDFs searchable (OCR)") },
                supportingContent = {
                    Text("Recognise text on-device and embed an invisible text layer so you can search, select and copy.")
                },
                trailingContent = {
                    Switch(checked = s.ocrEnabled, onCheckedChange = { on -> store.update { it.copy(ocrEnabled = on) } })
                },
            )
            HorizontalDivider()

            Section("Scanner")
            ListItem(
                headlineContent = { Text("Max pages per scan: ${s.pageLimit}") },
                supportingContent = {
                    Slider(
                        value = s.pageLimit.toFloat(),
                        onValueChange = { v -> store.update { it.copy(pageLimit = v.roundToInt()) } },
                        valueRange = 1f..100f,
                    )
                },
            )
            HorizontalDivider()

            Section("About")
            ListItem(
                headlineContent = { Text("Scanly — free forever") },
                supportingContent = {
                    Text("No ads, no watermarks, no account, no page limits. Everything runs on your device; your documents never leave it unless you share them.")
                },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
