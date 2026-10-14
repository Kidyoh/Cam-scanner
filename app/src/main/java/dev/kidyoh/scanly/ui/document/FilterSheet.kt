package dev.kidyoh.scanly.ui.document

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.kidyoh.scanly.data.PageEntity
import dev.kidyoh.scanly.pdf.PageFilter

/** Live previews of every filter on this page; tap one to apply it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    page: PageEntity,
    pageCount: Int,
    loadPreviews: suspend (Long) -> Map<PageFilter, android.graphics.Bitmap>,
    onDismiss: () -> Unit,
    onApply: (PageFilter, allPages: Boolean) -> Unit,
) {
    var allPages by remember { mutableStateOf(false) }
    val previews by produceState<Map<PageFilter, ImageBitmap>?>(null, page.id) {
        value = loadPreviews(page.id).mapValues { it.value.asImageBitmap() }
    }
    val current = PageFilter.of(page.filter)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Filters",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "Your original is always kept, so you can switch or undo anytime.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            val p = previews
            if (p == null) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(PageFilter.entries) { filter ->
                        val selected = filter == current
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(96.dp)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onApply(filter, allPages) },
                        ) {
                            p[filter]?.let { img ->
                                Image(
                                    img,
                                    contentDescription = filter.label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(96.dp, 128.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .border(
                                            if (selected) 3.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else Color.LightGray,
                                            MaterialTheme.shapes.small,
                                        ),
                                )
                            }
                            Text(
                                filter.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            if (pageCount > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Apply to all $pageCount pages", Modifier.weight(1f))
                    Switch(checked = allPages, onCheckedChange = { allPages = it })
                }
            }
        }
    }
}
