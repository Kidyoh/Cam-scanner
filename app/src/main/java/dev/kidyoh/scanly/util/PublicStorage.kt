package dev.kidyoh.scanly.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Writes files where the user can see them: page images in the Gallery (Pictures/Scanly)
 * and PDFs in the Files app (Download/Scanly). Android 10+ needs no permission for this;
 * Android 8–9 needs WRITE_EXTERNAL_STORAGE.
 */
object PublicStorage {
    const val FOLDER = "Scanly"

    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    fun saveImage(context: Context, src: File, displayName: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insert(
                context,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                src,
                "$displayName.jpg",
                "image/jpeg",
                Environment.DIRECTORY_PICTURES + "/" + FOLDER,
            )
        } else {
            legacyCopy(context, src, Environment.DIRECTORY_PICTURES, "$displayName.jpg", "image/jpeg")
        }

    /**
     * Saves [src] to Download/Scanly. If [existing] points at a copy we wrote earlier it is
     * overwritten (and renamed) instead of creating a duplicate.
     */
    fun savePdf(context: Context, src: File, displayName: String, existing: Uri? = null): Uri {
        val name = "$displayName.pdf"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (existing != null && existing.scheme == "content") {
                val updated = runCatching {
                    context.contentResolver.openOutputStream(existing, "wt")!!.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                    context.contentResolver.update(
                        existing,
                        ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
                        null,
                        null,
                    )
                }.isSuccess
                if (updated) return existing
            }
            return insert(
                context,
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                src,
                name,
                "application/pdf",
                Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER,
            )
        }
        existing?.takeIf { it.scheme == "file" }?.path?.let { old ->
            if (File(old).name != name) File(old).delete()
        }
        return legacyCopy(context, src, Environment.DIRECTORY_DOWNLOADS, name, "application/pdf", overwrite = true)
    }

    /** Human-readable location for messages, e.g. "Download/Scanly". */
    fun pdfFolder() = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
    fun imageFolder() = Environment.DIRECTORY_PICTURES + "/" + FOLDER

    private fun insert(
        context: Context,
        collection: Uri,
        src: File,
        name: String,
        mime: String,
        relativePath: String,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Couldn't create $name")
        try {
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun legacyCopy(
        context: Context,
        src: File,
        type: String,
        name: String,
        mime: String,
        overwrite: Boolean = false,
    ): Uri {
        if (needsPermission(context)) throw SecurityException("Storage permission needed to save files")
        val dir = File(Environment.getExternalStoragePublicDirectory(type), FOLDER).apply { mkdirs() }
        var dest = File(dir, name)
        if (!overwrite) {
            var n = 1
            while (dest.exists()) dest = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.')).also { n++ }
        }
        src.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(dest)
    }
}
