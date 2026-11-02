package com.estante.reader.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A selected text range, scoped to one imported publication. */
@Entity(
    tableName = "book_highlights",
    foreignKeys = [
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId", "chapterIndex"])],
)
data class BookHighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterHref: String,
    /** Character offsets in the chapter's visible text, excluding markup. */
    val startOffset: Int,
    val endOffset: Int,
    val selectedText: String,
    val prefix: String,
    val suffix: String,
    val colorId: String,
    val createdAt: Long,
)
