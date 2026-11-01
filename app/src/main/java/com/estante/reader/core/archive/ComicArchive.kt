package com.estante.reader.core.archive

import java.io.Closeable
import java.io.OutputStream

/** Common random-access page source consumed by the reader, thumbnails, OCR and analysis. */
interface ComicArchive : Closeable {
    val pages: List<ComicPage>
    val pageCount: Int get() = pages.size

    /** Copies one expanded page to [output], enforcing the archive's per-entry safety limit. */
    fun copyPageTo(index: Int, output: OutputStream)

    fun readPage(index: Int): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        copyPageTo(index, output)
        output.toByteArray()
    }
}
