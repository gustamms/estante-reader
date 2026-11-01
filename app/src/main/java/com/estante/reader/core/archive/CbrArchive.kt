package com.estante.reader.core.archive

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * RAR-backed comic reader. RAR can be solid and is not a random-access container, so pages are
 * streamed once into a bounded private cache. This gives the reader stable page-by-index access.
 */
class CbrArchive private constructor(
    private val extractedPages: List<File>,
    private val temporaryDirectory: File,
    override val pages: List<ComicPage>,
    private val limits: CbzLimits,
) : ComicArchive {
    override val pageCount: Int get() = pages.size

    override fun copyPageTo(index: Int, output: OutputStream) {
        val source = extractedPages.getOrNull(index) ?: throw IndexOutOfBoundsException("No page at index $index")
        if (!source.isFile || source.length() > limits.maxEntryBytes) {
            throw InvalidComicArchiveException("Extracted CBR page is missing or exceeds the allowed size")
        }
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                CbzArchive.enforceEntryLimit(copied, limits)
                output.write(buffer, 0, count)
            }
        }
    }

    override fun close() {
        temporaryDirectory.deleteRecursively()
    }

    companion object {
        /**
         * Opens a single-volume RAR archive and materializes its image entries in [cacheRoot].
         * [cacheRoot] must be private app storage; the returned archive owns its temporary folder.
         */
        fun openFile(file: File, cacheRoot: File, limits: CbzLimits = CbzLimits()): CbrArchive {
            if (!file.isFile || !file.canRead()) throw InvalidComicArchiveException("CBR file is unavailable")
            if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
                throw InvalidComicArchiveException("Could not prepare private CBR cache")
            }
            val targetDirectory = File(cacheRoot, "cbr-${UUID.randomUUID()}")
            if (!targetDirectory.mkdir()) throw InvalidComicArchiveException("Could not prepare private CBR cache")

            val extracted = mutableListOf<Pair<ComicPage, File>>()
            var totalExpanded = 0L
            var entryCount = 0
            var archive: Archive? = null
            try {
                archive = Archive(file)
                while (true) {
                    val header = archive.nextFileHeader() ?: break
                    entryCount++
                    if (entryCount > limits.maxEntries) throw ComicArchiveLimitException("Archive has too many entries")
                    val name = header.fileName.replace('\\', '/')
                    CbzArchive.validateEntryName(name)
                    if (header.isEncrypted) throw PasswordRequiredComicArchiveException()
                    if (header.isSplitAfter || header.isSplitBefore) {
                        throw UnsupportedComicArchiveException("Multi-volume CBR archives are not supported yet")
                    }
                    if (header.isDirectory || !header.isFileHeader) continue

                    val declaredSize = header.fullUnpackSize
                    if (declaredSize > limits.maxEntryBytes) {
                        throw ComicArchiveLimitException("Archive entry expands beyond the allowed size")
                    }
                    val pageImage = CbzArchive.isPage(name)
                    if (pageImage && extracted.size >= limits.maxPages) {
                        throw ComicArchiveLimitException("Archive has too many page images")
                    }

                    val entryFile = if (pageImage) File(targetDirectory, "page-${entryCount}.image") else null
                    val sink = entryFile?.let { BufferedOutputStream(FileOutputStream(it)) } ?: CountingDiscardOutputStream()
                    try {
                        val bounded = EntryLimitOutputStream(sink, limits.maxEntryBytes, limits.maxExpandedBytes - totalExpanded)
                        archive.extractFile(header, bounded)
                        bounded.flush()
                        val actualSize = bounded.bytesWritten
                        totalExpanded += actualSize
                        if (totalExpanded > limits.maxExpandedBytes) {
                            throw ComicArchiveLimitException("Archive expands beyond the allowed size")
                        }
                        if (pageImage && actualSize > 0L) {
                            val page = ComicPage(-1, name, actualSize)
                            extracted += page to requireNotNull(entryFile)
                        } else {
                            entryFile?.delete()
                        }
                    } finally {
                        sink.close()
                    }
                }
                if (extracted.isEmpty()) throw InvalidComicArchiveException("Archive contains no supported page images")
                val ordered = extracted.sortedWith { a, b -> CbzArchive.naturalCompare(a.first.entryName, b.first.entryName) }
                val pages = ordered.mapIndexed { index, item -> item.first.copy(index = index) }
                return CbrArchive(ordered.map { it.second }, targetDirectory, pages, limits)
            } catch (error: Exception) {
                targetDirectory.deleteRecursively()
                when (error) {
                    is ComicArchiveLimitException -> throw error
                    is InvalidComicArchiveException -> throw error
                    is PasswordRequiredComicArchiveException -> throw error
                    is UnsupportedComicArchiveException -> throw error
                }
                val message = error.message.orEmpty()
                if (message.contains("password", ignoreCase = true) || message.contains("encrypted", ignoreCase = true)) {
                    throw PasswordRequiredComicArchiveException()
                }
                if (message.contains("volume", ignoreCase = true) || message.contains("next volume", ignoreCase = true)) {
                    throw UnsupportedComicArchiveException("Multi-volume CBR archives are not supported yet", error)
                }
                throw InvalidComicArchiveException("Could not index CBR archive", error)
            } finally {
                runCatching { archive?.close() }
            }
        }
    }
}

/** Writes to a sink while enforcing both per-entry and remaining aggregate expansion limits. */
private class EntryLimitOutputStream(
    private val sink: OutputStream,
    private val entryLimit: Long,
    private val remainingTotalLimit: Long,
) : OutputStream() {
    var bytesWritten: Long = 0
        private set

    override fun write(value: Int) {
        ensureCapacity(1)
        sink.write(value)
        bytesWritten++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length.toLong())
        sink.write(buffer, offset, length)
        bytesWritten += length
    }

    override fun flush() = sink.flush()

    override fun close() = sink.close()

    private fun ensureCapacity(incoming: Long) {
        if (incoming < 0 || bytesWritten > entryLimit - incoming) {
            throw ComicArchiveLimitException("Archive entry expands beyond the allowed size")
        }
        if (bytesWritten > remainingTotalLimit - incoming) {
            throw ComicArchiveLimitException("Archive expands beyond the allowed size")
        }
    }
}

private class CountingDiscardOutputStream : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
}

class PasswordRequiredComicArchiveException : IllegalArgumentException("CBR archive requires a password")
class UnsupportedComicArchiveException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

