package com.estante.reader.core.archive

import com.estante.reader.core.data.ComicFormat
import java.io.File
import java.io.RandomAccessFile

/** Selects the page renderer from file contents, not from a potentially misleading extension. */
object PageDocumentFactory {
    fun openFile(file: File, cbrCacheRoot: File, limits: CbzLimits = CbzLimits()): OpenedPageDocument {
        val header = try {
            RandomAccessFile(file, "r").use { input ->
                ByteArray(minOf(PDF_HEADER_SCAN_BYTES.toLong(), input.length()).toInt()).also(input::readFully)
            }
        } catch (error: Exception) {
            throw InvalidComicArchiveException("O arquivo não está disponível para leitura.", error)
        }

        return when {
            header.isZipSignature() -> OpenedPageDocument(CbzArchive.openFile(file, limits), ComicFormat.CBZ)
            header.isRarSignature() -> OpenedPageDocument(CbrArchive.openFile(file, cbrCacheRoot, limits), ComicFormat.CBR)
            header.containsPdfHeader() -> OpenedPageDocument(PdfPageDocument.openFile(file), ComicFormat.PDF)
            else -> throw UnsupportedComicArchiveException("Formato não suportado. Use PDF, CBZ ou CBR.")
        }
    }

    private fun ByteArray.isZipSignature(): Boolean =
        size >= 4 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte() &&
            (this[2] == 3.toByte() || this[2] == 5.toByte() || this[2] == 7.toByte())

    private fun ByteArray.isRarSignature(): Boolean =
        size >= 7 && this[0] == 'R'.code.toByte() && this[1] == 'a'.code.toByte() &&
            this[2] == 'r'.code.toByte() && this[3] == '!'.code.toByte() &&
            this[4] == 0x1a.toByte() && this[5] == 0x07.toByte() &&
            (this[6] == 0x00.toByte() || size >= 8 && this[6] == 0x01.toByte() && this[7] == 0x00.toByte())

    private fun ByteArray.containsPdfHeader(): Boolean {
        val marker = "%PDF-".encodeToByteArray()
        return (0..size - marker.size).any { start ->
            marker.indices.all { offset -> this[start + offset] == marker[offset] }
        }
    }

    private const val PDF_HEADER_SCAN_BYTES = 1_024
}
