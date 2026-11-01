package com.estante.reader.core.archive

import java.io.File
import java.io.RandomAccessFile

/** Selects a page reader from the archive signature, not an editable filename extension. */
object ComicArchiveFactory {
    fun openFile(file: File, cbrCacheRoot: File, limits: CbzLimits = CbzLimits()): ComicArchive {
        val signature = try {
            RandomAccessFile(file, "r").use { input ->
                ByteArray(minOf(8L, input.length()).toInt()).also(input::readFully)
            }
        } catch (error: Exception) {
            throw InvalidComicArchiveException("Comic archive is unavailable", error)
        }

        return when {
            signature.isZipSignature() -> CbzArchive.openFile(file, limits)
            signature.isRarSignature() -> CbrArchive.openFile(file, cbrCacheRoot, limits)
            else -> throw UnsupportedComicArchiveException("File is not a supported CBZ or CBR archive")
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
}
