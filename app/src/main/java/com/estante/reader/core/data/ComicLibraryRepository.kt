package com.estante.reader.core.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/** Structured local library access; filesystem cleanup is intentionally coordinated by import flow. */
class ComicLibraryRepository(private val dao: ComicDao) {
    fun observeLibrary(): Flow<List<ComicEntity>> = dao.observeLibrary()

    suspend fun getById(id: String): ComicEntity? = dao.findById(id)

    suspend fun findByContentHash(contentHash: String): ComicEntity? = dao.findByContentHash(contentHash)

    suspend fun findBySourceUri(uri: Uri): ComicEntity? = dao.findBySourceUri(uri.toString())

    suspend fun upsert(comic: ComicEntity) = dao.upsert(comic)

    suspend fun beginImport(
        id: String,
        title: String,
        displayName: String,
        format: ComicFormat,
        sourceUri: Uri?,
        managedPath: String,
        now: Long = System.currentTimeMillis(),
    ) {
        dao.upsert(
            ComicEntity(
                id = id,
                contentHash = null,
                title = title,
                displayName = displayName,
                format = format,
                managedPath = managedPath,
                sourceUri = sourceUri?.toString(),
                pageCount = 0,
                coverPageIndex = 0,
                coverPath = null,
                importState = ComicImportState.IMPORTING,
                importError = null,
                addedAt = now,
                lastOpenedAt = null,
                lastPageIndex = 0,
                lastRegionId = null,
                completed = false,
            ),
        )
    }

    suspend fun markReady(
        id: String,
        contentHash: String,
        managedPath: String,
        pageCount: Int,
        coverPageIndex: Int = 0,
        coverPath: String? = null,
    ) {
        require(contentHash.matches(Regex("[0-9a-fA-F]{64}"))) { "Expected SHA-256 content hash" }
        require(pageCount > 0)
        require(coverPageIndex in 0 until pageCount)
        check(dao.markReady(id, contentHash.lowercase(), managedPath, pageCount, coverPageIndex, coverPath) == 1) {
            "Import record no longer exists"
        }
    }

    suspend fun markFailed(id: String, reason: String) {
        dao.markFailed(id, reason.take(MAX_ERROR_LENGTH))
    }

    suspend fun updateProgress(
        id: String,
        pageIndex: Int,
        regionId: String? = null,
        completed: Boolean = false,
        openedAt: Long = System.currentTimeMillis(),
    ) {
        require(pageIndex >= 0)
        dao.updateProgress(id, pageIndex, regionId, openedAt, completed)
    }

    suspend fun recordOpened(
        id: String,
        title: String,
        pageCount: Int,
        pageIndex: Int,
        regionId: String? = null,
        openedAt: Long = System.currentTimeMillis(),
    ) {
        require(pageCount > 0 && pageIndex in 0 until pageCount)
        dao.recordOpened(id, title, pageCount, pageIndex, regionId, openedAt)
    }

    suspend fun updateCover(id: String, pageIndex: Int, coverPath: String?) {
        require(pageIndex >= 0)
        dao.updateCover(id, pageIndex, coverPath)
    }

    /** Removes only the database row; caller deletes managed files after active readers release them. */
    suspend fun remove(id: String) {
        dao.deleteById(id)
    }

    /** One-time, idempotent bridge from the old DataStore last-document preference. */
    suspend fun migrateLegacyLastRead(uri: Uri, pageIndex: Int, title: String): ComicEntity {
        require(pageIndex >= 0)
        val uriText = uri.toString()
        val id = "legacy-${sha256(uriText)}"
        val existing = dao.findById(id)
        if (existing != null) {
            return existing
        }
        val format = if (uri.lastPathSegment.orEmpty().substringAfterLast('.', "").equals("cbr", ignoreCase = true)) {
            ComicFormat.CBR
        } else {
            ComicFormat.CBZ
        }
        val migrated = ComicEntity(
            id = id,
            contentHash = null,
            title = title.ifBlank { "Quadrinho importado" },
            displayName = uri.lastPathSegment?.substringAfterLast('/') ?: title,
            format = format,
            managedPath = null,
            sourceUri = uriText,
            pageCount = 0,
            coverPageIndex = 0,
            coverPath = null,
            importState = ComicImportState.READY,
            importError = null,
            addedAt = System.currentTimeMillis(),
            lastOpenedAt = System.currentTimeMillis(),
            lastPageIndex = pageIndex,
            lastRegionId = null,
            completed = false,
        )
        dao.upsert(migrated)
        return migrated
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_ERROR_LENGTH = 240 }
}
