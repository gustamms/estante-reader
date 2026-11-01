package com.estante.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicDao {
    @Query(
        "SELECT * FROM comic_publications " +
            "WHERE importState != 'FAILED' " +
            "ORDER BY COALESCE(lastOpenedAt, addedAt) DESC, addedAt DESC",
    )
    fun observeLibrary(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comic_publications WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ComicEntity?

    @Query("SELECT * FROM comic_publications WHERE contentHash = :contentHash LIMIT 1")
    suspend fun findByContentHash(contentHash: String): ComicEntity?

    @Query("SELECT * FROM comic_publications WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun findBySourceUri(sourceUri: String): ComicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(comic: ComicEntity)

    @Query(
        "UPDATE comic_publications SET importState = 'READY', importError = NULL, " +
            "contentHash = :contentHash, managedPath = :managedPath, pageCount = :pageCount, " +
            "coverPageIndex = :coverPageIndex, coverPath = :coverPath " +
            "WHERE id = :id",
    )
    suspend fun markReady(
        id: String,
        contentHash: String,
        managedPath: String,
        pageCount: Int,
        coverPageIndex: Int,
        coverPath: String?,
    ): Int

    @Query("UPDATE comic_publications SET importState = 'FAILED', importError = :reason WHERE id = :id")
    suspend fun markFailed(id: String, reason: String): Int

    @Query(
        "UPDATE comic_publications SET lastPageIndex = :pageIndex, lastRegionId = :regionId, " +
            "lastOpenedAt = :openedAt, completed = :completed WHERE id = :id",
    )
    suspend fun updateProgress(
        id: String,
        pageIndex: Int,
        regionId: String?,
        openedAt: Long,
        completed: Boolean,
    ): Int

    @Query(
        "UPDATE comic_publications SET title = :title, pageCount = :pageCount, " +
            "lastPageIndex = :pageIndex, lastRegionId = :regionId, " +
            "lastOpenedAt = :openedAt WHERE id = :id",
    )
    suspend fun recordOpened(
        id: String,
        title: String,
        pageCount: Int,
        pageIndex: Int,
        regionId: String?,
        openedAt: Long,
    ): Int

    @Query("UPDATE comic_publications SET coverPageIndex = :pageIndex, coverPath = :coverPath WHERE id = :id")
    suspend fun updateCover(id: String, pageIndex: Int, coverPath: String?): Int

    @Query("DELETE FROM comic_publications WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
