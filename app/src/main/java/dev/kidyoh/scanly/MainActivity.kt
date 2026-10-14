package dev.kidyoh.scanly

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.kidyoh.scanly.ui.document.DocumentScreen
import dev.kidyoh.scanly.ui.home.HomeScreen
import dev.kidyoh.scanly.ui.settings.SettingsScreen
import dev.kidyoh.scanly.ui.theme.ScanlyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ScanlyTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            openDocument = { nav.navigate("doc/$it") },
                            openSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("doc/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        DocumentScreen(
                            documentId = it.arguments!!.getLong("id"),
                            onBack = { nav.popBackStack() },
                            openDocument = { id ->
                                nav.navigate("doc/$id") { popUpTo("home") }
                            },
                        )
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val isPdf = intent.type == "application/pdf" ||
            uris.all { contentResolver.getType(it) == "application/pdf" }
        (application as ScanlyApp).incoming.value = IncomingShare(uris, isPdf)
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelable(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableList(key: String): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java) else getParcelableArrayListExtra(key))
            .orEmpty()
}
