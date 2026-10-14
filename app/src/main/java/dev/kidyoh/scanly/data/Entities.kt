package dev.kidyoh.scanly.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int = 0,
    val pdfPath: String,
    val thumbnailPath: String? = null,
    val fileSize: Long = 0,
    val favorite: Boolean = false,
    /** Concatenated OCR text of every page, used for full-text search. */
    val searchText: String = "",
    /** The copy auto-saved to Downloads/Scanly, so it can be updated in place after edits. */
    val publicPdfUri: String? = null,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val position: Int,
    val imagePath: String,
    /** Serialized [dev.kidyoh.scanly.pdf.OcrResult]; null when OCR is disabled or failed. */
    val ocrJson: String? = null,
    /** Unfiltered image, kept so filters can be changed or undone without quality loss. */
    val originalPath: String? = null,
    @ColumnInfo(defaultValue = "ORIGINAL") val filter: String = "ORIGINAL",
)
