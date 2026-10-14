package dev.kidyoh.scanly.util

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Returns `runWithStorage(action)`: runs [action] right away on Android 10+, or after asking
 * for storage permission on Android 8–9 (the only versions that need it to save to Gallery/Downloads).
 */
@Composable
fun rememberStorageAccess(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pending?.invoke()
        } else {
            Toast.makeText(context, "Storage permission is needed to save to your phone", Toast.LENGTH_LONG).show()
        }
        pending = null
    }
    return remember(launcher) {
        { action ->
            if (!PublicStorage.needsPermission(context)) {
                action()
            } else {
                pending = action
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}
