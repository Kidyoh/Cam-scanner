package dev.kidyoh.scanly.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object ImageUtils {

    /**
     * Decodes [uri], honours EXIF orientation, downsamples so the longest side is at most
     * [maxSide] and writes a clean JPEG to [dest].
     */
    fun normalize(context: Context, uri: Uri, dest: File, maxSide: Int, quality: Float): Boolean {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false

        val rotation = resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).rotationDegrees }.getOrDefault(0)
        } ?: 0

        val scaled = scaleDown(decoded, maxSide)
        val oriented = if (rotation != 0) rotate(scaled, rotation.toFloat()) else scaled
        write(oriented, dest, quality)
        if (oriented !== decoded) decoded.recycle()
        oriented.recycle()
        return true
    }

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
    }

    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun rotateFile(src: File, dest: File, degrees: Float) {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return
        val rotated = rotate(bmp, degrees)
        write(rotated, dest, 0.92f)
        bmp.recycle()
        rotated.recycle()
    }

    fun write(bmp: Bitmap, dest: File, quality: Float) {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), it) }
    }

    fun makeThumbnail(src: File, dest: File, width: Int = 480) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return
        val scaled = scaleDown(bmp, (width * 1.5f).toInt())
        write(scaled, dest, 0.8f)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
    }
}
