package dev.kidyoh.scanly

import android.app.Application
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dev.kidyoh.scanly.data.AppDatabase
import dev.kidyoh.scanly.data.DocumentRepository
import dev.kidyoh.scanly.data.SettingsStore
import dev.kidyoh.scanly.pdf.OcrEngine
import kotlinx.coroutines.flow.MutableStateFlow

/** Files shared into the app from elsewhere, waiting for the home screen to import them. */
data class IncomingShare(val uris: List<Uri>, val isPdf: Boolean)

class ScanlyApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var repository: DocumentRepository
        private set

    val incoming = MutableStateFlow<IncomingShare?>(null)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        settings = SettingsStore(this)
        repository = DocumentRepository(this, AppDatabase.create(this).documentDao(), settings, OcrEngine(this))
    }
}
