package com.estante.reader.core.archive

import com.estante.reader.core.data.ComicFormat
import java.io.Closeable

/** Random-access source of rendered page images, regardless of the source file format. */
interface PageDocument : Closeable {
    val pageCount: Int
    fun readPage(index: Int): ByteArray

    /** Lets renderers avoid producing full-resolution images for thumbnails and recognition. */
    fun readPage(index: Int, maxDimension: Int, maxPixels: Long): ByteArray = readPage(index)
}

data class OpenedPageDocument(
    val document: PageDocument,
    val format: ComicFormat,
)
