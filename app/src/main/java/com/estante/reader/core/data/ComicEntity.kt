package com.estante.reader.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ComicFormat { CBZ, CBR }
enum class ComicImportState { IMPORTING, READY, FAILED }

/** Durable metadata for one locally managed or SAF-linked comic. */
@Entity(
    tableName = "comic_publications",
    indices = [
        Index(value = ["contentHash"], unique = true),
        Index(value = ["lastOpenedAt"]),
        Index(value = ["sourceUri"]),
    ],
)
data class ComicEntity(
    @PrimaryKey val id: String,
    /** SHA-256 of imported bytes; nullable only while importing or for a migrated SAF entry. */
    val contentHash: String?,
    val title: String,
    val displayName: String,
    val format: ComicFormat,
    /** Private durable copy. Null for a legacy document that still points to its SAF URI. */
    val managedPath: String?,
    val sourceUri: String?,
    val pageCount: Int,
    val coverPageIndex: Int,
    /** Relative app-private path for a generated thumbnail; it can be regenerated. */
    val coverPath: String?,
    val importState: ComicImportState,
    val importError: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val lastPageIndex: Int,
    val lastRegionId: String?,
    val completed: Boolean,
)
