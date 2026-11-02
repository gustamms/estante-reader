package com.estante.reader.core.data

import kotlinx.coroutines.flow.Flow

class BookHighlightRepository(private val dao: BookHighlightDao) {
    fun observeForBook(bookId: String): Flow<List<BookHighlightEntity>> = dao.observeForBook(bookId)

    suspend fun insert(highlight: BookHighlightEntity) = dao.insert(highlight)

    suspend fun updateColor(id: String, bookId: String, colorId: String) =
        dao.updateColor(id, bookId, colorId)

    suspend fun delete(id: String, bookId: String) = dao.delete(id, bookId)
}
