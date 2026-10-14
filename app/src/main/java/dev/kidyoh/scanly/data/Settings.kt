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
    HIGH("High (best for printing)", 3000, 0.92f),
    BALANCED("Balanced", 2200, 0.82f),
    COMPACT("Compact (smallest files)", 1500, 0.65f),
}

data class AppSettings(
    val pageSize: PageSize = PageSize.AUTO,
    val quality: Quality = Quality.BALANCED,
    val ocrEnabled: Boolean = true,
    val pageLimit: Int = 50,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read() = AppSettings(
        pageSize = enumValueOrDefault(prefs.getString("pageSize", null), PageSize.AUTO),
        quality = enumValueOrDefault(prefs.getString("quality", null), Quality.BALANCED),
        ocrEnabled = prefs.getBoolean("ocr", true),
        pageLimit = prefs.getInt("pageLimit", 50),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val new = transform(_settings.value)
        prefs.edit()
            .putString("pageSize", new.pageSize.name)
            .putString("quality", new.quality.name)
            .putBoolean("ocr", new.ocrEnabled)
            .putInt("pageLimit", new.pageLimit)
            .apply()
        _settings.value = new
    }

    private inline fun <reified E : Enum<E>> enumValueOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
