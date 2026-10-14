package dev.kidyoh.scanly.ui.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import dev.kidyoh.scanly.data.PageEntity
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val inkColors = listOf(Color(0xFF111111), Color(0xFF1A3DBF), Color(0xFFB00020))

/**
 * Two-step signing: draw a signature (or reuse the saved one), then drag/pinch it into place.
 * [onDone] receives the signature plus its position as fractions of the page size.
 */
@Composable
fun SignatureFlow(
    page: PageEntity,
    saved: Bitmap?,
    onDismiss: () -> Unit,
    onDone: (signature: Bitmap, remember: Boolean, left: Float, top: Float, width: Float) -> Unit,
) {
    var signature by remember { mutableStateOf<Bitmap?>(null) }
    var rememberIt by remember { mutableStateOf(true) }
    var drawing by remember { mutableStateOf(saved == null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") }
                    Text(
                        when {
                            signature != null -> "Drag & pinch to place"
                            drawing -> "Draw your signature"
                            else -> "Sign page"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                val sig = signature
                when {
                    sig != null -> Placement(page, sig, onBack = { signature = null }) { l, t, w ->
                        onDone(sig, rememberIt, l, t, w)
                    }
                    drawing -> SignaturePad(
                        rememberIt = rememberIt,
                        onRememberChange = { rememberIt = it },
                        onUseSaved = saved?.let { { drawing = false } },
                    ) { signature = it }
                    else -> SavedSignature(saved!!, onUse = { rememberIt = false; signature = saved }, onDrawNew = { drawing = true })
                }
            }
        }
    }
}

@Composable
private fun SavedSignature(saved: Bitmap, onUse: () -> Unit, onDrawNew: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            saved.asImageBitmap(),
            contentDescription = "Saved signature",
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Color.White, MaterialTheme.shapes.medium)
                .padding(16.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) { Text("Use saved signature") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDrawNew, modifier = Modifier.fillMaxWidth()) { Text("Draw a new one") }
    }
}

@Composable
private fun SignaturePad(
    rememberIt: Boolean,
    onRememberChange: (Boolean) -> Unit,
    onUseSaved: (() -> Unit)?,
    onFinished: (Bitmap) -> Unit,
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(inkColors[0]) }
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.White)
                .border(1.dp, Color.LightGray, MaterialTheme.shapes.medium),
        ) {
            Text(
                "Sign here",
                color = Color.LightGray,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { current = listOf(it) },
                            onDrag = { change, _ -> current = current + change.position },
                            onDragEnd = {
                                if (current.size > 1) strokes.add(current)
                                current = emptyList()
                            },
                        )
                    },
            ) {
                (strokes + listOf(current)).forEach { pts ->
                    if (pts.size < 2) return@forEach
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, color, style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            inkColors.forEach { c ->
                Box(
                    Modifier
                        .size(32.dp)
                        .background(c, CircleShape)
                        .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { color = c },
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberIt, onCheckedChange = onRememberChange)
            Text("Remember this signature", Modifier.weight(1f))
            onUseSaved?.let { TextButton(onClick = it) { Text("Use saved") } }
        }
        Button(
            onClick = { renderSignature(strokes, color, strokePx)?.let(onFinished) },
            enabled = strokes.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Next: place on page") }
    }
}

/** Renders strokes at 2× into a tightly-cropped transparent bitmap. */
private fun renderSignature(strokes: List<List<Offset>>, color: Color, strokePx: Float): Bitmap? {
    val pts = strokes.flatten()
    if (pts.isEmpty()) return null
    val pad = strokePx * 2
    val minX = pts.minOf { it.x } - pad
    val minY = pts.minOf { it.y } - pad
    val w = (pts.maxOf { it.x } + pad - minX).coerceAtLeast(1f)
    val h = (pts.maxOf { it.y } + pad - minY).coerceAtLeast(1f)
    val scale = 2f
    val bmp = Bitmap.createBitmap((w * scale).roundToInt(), (h * scale).roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = strokePx * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { s ->
        val path = Path()
        path.moveTo((s[0].x - minX) * scale, (s[0].y - minY) * scale)
        s.drop(1).forEach { path.lineTo((it.x - minX) * scale, (it.y - minY) * scale) }
        canvas.drawPath(path, paint)
    }
    return bmp
}

@Composable
private fun Placement(page: PageEntity, signature: Bitmap, onBack: () -> Unit, onConfirm: (Float, Float, Float) -> Unit) {
    val imageSize = remember(page.imagePath) {
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(page.imagePath, this)
        }.let { max(1, it.outWidth) to max(1, it.outHeight) }
    }
    val density = LocalDensity.current
    var placement by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(12.dp)) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val scale = min(boxW / imageSize.first, boxH / imageSize.second)
            val dw = imageSize.first * scale
            val dh = imageSize.second * scale
            val left = (boxW - dw) / 2
            val top = (boxH - dh) / 2

            var sigW by remember(dw) { mutableFloatStateOf(dw * 0.35f) }
            var center by remember(dw) { mutableStateOf(Offset(left + dw * 0.68f, top + dh * 0.82f)) }
            val sigH = sigW * signature.height / signature.width

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(dw) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            sigW = (sigW * zoom).coerceIn(dw * 0.06f, dw)
                            center = Offset(
                                (center.x + pan.x).coerceIn(left, left + dw),
                                (center.y + pan.y).coerceIn(top, top + dh),
                            )
                        }
                    },
            ) {
                AsyncImage(
                    model = File(page.imagePath),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Image(
                    signature.asImageBitmap(),
                    contentDescription = "Signature",
                    modifier = Modifier
                        .offset { IntOffset((center.x - sigW / 2).roundToInt(), (center.y - sigH / 2).roundToInt()) }
                        .size(with(density) { sigW.toDp() }, with(density) { sigH.toDp() })
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                )
            }
            // Position as fractions of the page, read by the "Sign page" button below.
            SideEffect {
                placement = Triple((center.x - sigW / 2 - left) / dw, (center.y - sigH / 2 - top) / dh, sigW / dw)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Redraw") }
            Button(
                onClick = { placement?.let { (l, t, w) -> onConfirm(l, t, w) } },
                modifier = Modifier.weight(1f),
            ) { Text("Sign page") }
        }
    }
}
