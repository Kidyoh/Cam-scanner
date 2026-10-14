package dev.kidyoh.scanly.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query(
        """
        SELECT * FROM documents
        WHERE pageCount > 0
          AND (:query = '' OR title LIKE '%' || :query || '%' OR searchText LIKE '%' || :query || '%')
          AND (:favoritesOnly = 0 OR favorite = 1)
        ORDER BY updatedAt DESC
        """,
    )
    fun observeDocuments(query: String, favoritesOnly: Boolean): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: Long): DocumentEntity?

    @Insert
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Delete
    suspend fun deleteDocument(doc: DocumentEntity)

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    fun observePages(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    suspend fun getPages(documentId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getPage(id: Long): PageEntity?

    @Insert
    suspend fun insertPages(pages: List<PageEntity>)

    @Update
    suspend fun updatePages(pages: List<PageEntity>)

    @Delete
    suspend fun deletePage(page: PageEntity)

    @Transaction
    suspend fun replacePageOrder(pages: List<PageEntity>) {
        updatePages(pages.mapIndexed { i, p -> p.copy(position = i) })
    }
}
