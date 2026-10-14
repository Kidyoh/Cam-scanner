package dev.kidyoh.scanly

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kidyoh.scanly.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Upgrading from a database written by version 1.0 of the app must keep every document. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** Recreates the exact v1 database from the exported schema (tests run from the module dir). */
    private fun createV1(path: File) {
        val schema = JSONObject(File("schemas/dev.kidyoh.scanly.data.AppDatabase/1.json").readText()).getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            e.optJSONArray("indices")?.let { idx ->
                for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL(
            "INSERT INTO documents (id, title, createdAt, updatedAt, pageCount, pdfPath, thumbnailPath, fileSize, favorite, searchText) " +
                "VALUES (1, 'Old scan', 1, 2, 1, '/x.pdf', NULL, 10, 1, 'hello')",
        )
        db.execSQL("INSERT INTO pages (id, documentId, position, imagePath, ocrJson) VALUES (1, 1, 0, '/p.jpg', NULL)")
        db.version = 1
        db.close()
    }

    @Test
    fun v1Data_survivesUpgrade() = runBlocking {
        val path = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        createV1(path)

        // Opening with the current schema runs the auto-migration and validates the result.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, path.absolutePath).build()
        val doc = db.documentDao().getDocument(1)!!
        assertEquals("Old scan", doc.title)
        assertNull(doc.publicPdfUri)
        val page = db.documentDao().getPages(1).single()
        assertEquals("ORIGINAL", page.filter)
        assertNull(page.originalPath)
        db.close()
    }
}
