package com.estante.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookHighlightDao {
    @Query("SELECT * FROM book_highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC, startOffset ASC")
    fun observeForBook(bookId: String): Flow<List<BookHighlightEntity>>

    @Query("SELECT * FROM book_highlights WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BookHighlightEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(highlight: BookHighlightEntity)

    @Query("UPDATE book_highlights SET colorId = :colorId WHERE id = :id AND bookId = :bookId")
    suspend fun updateColor(id: String, bookId: String, colorId: String): Int

    @Query("DELETE FROM book_highlights WHERE id = :id AND bookId = :bookId")
    suspend fun delete(id: String, bookId: String): Int
}
