package dev.kidyoh.scanly.pdf

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class OcrLine(val text: String, val box: Rect)

/** Recognized text of one page, with line positions in the page image's pixel space. */
data class OcrResult(val width: Int, val height: Int, val lines: List<OcrLine>) {
    val text: String get() = lines.joinToString("\n") { it.text }

    fun toJson(): String = JSONObject().apply {
        put("w", width)
        put("h", height)
        put("lines", JSONArray().apply {
            lines.forEach { l ->
                put(JSONObject().apply {
                    put("t", l.text)
                    put("b", JSONArray(listOf(l.box.left, l.box.top, l.box.right, l.box.bottom)))
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(json: String?): OcrResult? = runCatching {
            val o = JSONObject(json ?: return null)
            val arr = o.getJSONArray("lines")
            val lines = (0 until arr.length()).map { i ->
                val l = arr.getJSONObject(i)
                val b = l.getJSONArray("b")
                OcrLine(l.getString("t"), Rect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)))
            }
            OcrResult(o.getInt("w"), o.getInt("h"), lines)
        }.getOrNull()
    }
}

/** On-device text recognition via ML Kit. Free and works offline. */
class OcrEngine(private val context: Context) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(file: File): OcrResult? = runCatching {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        val result = recognizer.process(image).await()
        val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { OcrLine(line.text, it) }
        }
        OcrResult(image.width, image.height, lines)
    }.getOrNull()
}
