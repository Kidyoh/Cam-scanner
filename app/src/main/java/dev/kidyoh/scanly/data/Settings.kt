package dev.kidyoh.scanly.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PageSize(val label: String) {
    AUTO("Fit to image"),
    A4("A4"),
    LETTER("US Letter"),
}

enum class Quality(val label: String, val maxSide: Int, val jpegQuality: Float) {
    MAXIMUM("Maximum detail (full camera resolution)", 8000, 0.95f),
    HIGH("High (great for printing)", 3000, 0.90f),
    BALANCED("Balanced", 2200, 0.80f),
    COMPACT("Compact (smallest files)", 1500, 0.65f),
}

enum class SortOrder(val label: String) {
    NEWEST("Recently modified"),
    OLDEST("Oldest first"),
    NAME("Name (A–Z)"),
    SIZE("Largest first"),
    PAGES("Most pages"),
}

data class AppSettings(
    val pageSize: PageSize = PageSize.AUTO,
    val quality: Quality = Quality.HIGH,
    val ocrEnabled: Boolean = true,
    val pageLimit: Int = 50,
    /** Keep an up-to-date copy of every PDF in Downloads/Scanly. */
    val autoSavePdf: Boolean = true,
    /** Also save every scanned page to the gallery (Pictures/Scanly). */
    val autoSaveImages: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NEWEST,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            pageSize = enumValueOrDefault(prefs.getString("pageSize", null), d.pageSize),
            quality = enumValueOrDefault(prefs.getString("quality", null), d.quality),
            ocrEnabled = prefs.getBoolean("ocr", d.ocrEnabled),
            pageLimit = prefs.getInt("pageLimit", d.pageLimit),
            autoSavePdf = prefs.getBoolean("autoSavePdf", d.autoSavePdf),
            autoSaveImages = prefs.getBoolean("autoSaveImages", d.autoSaveImages),
            sortOrder = enumValueOrDefault(prefs.getString("sortOrder", null), d.sortOrder),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val new = transform(_settings.value)
        prefs.edit()
            .putString("pageSize", new.pageSize.name)
            .putString("quality", new.quality.name)
            .putBoolean("ocr", new.ocrEnabled)
            .putInt("pageLimit", new.pageLimit)
            .putBoolean("autoSavePdf", new.autoSavePdf)
            .putBoolean("autoSaveImages", new.autoSaveImages)
            .putString("sortOrder", new.sortOrder.name)
            .apply()
        _settings.value = new
    }

    private inline fun <reified E : Enum<E>> enumValueOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
