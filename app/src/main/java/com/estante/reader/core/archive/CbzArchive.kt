package com.estante.reader.core.archive

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

/** Hard limits are applied to bytes actually expanded, never just ZIP metadata. */
data class CbzLimits(
    val maxEntries: Int = 10_000,
    val maxPages: Int = 4_000,
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxExpandedBytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(maxEntries > 0 && maxPages > 0)
        require(maxEntryBytes > 0 && maxExpandedBytes > 0)
        require(maxEntryBytes <= maxExpandedBytes)
    }
}

data class ComicPage(val index: Int, val entryName: String, val uncompressedSize: Long)

class InvalidComicArchiveException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class ComicArchiveLimitException(message: String) : IllegalArgumentException(message)

/**
 * Re-openable CBZ reader for SAF sources. Indexing and page reads stream through the archive and
 * keep only entry metadata in memory. Extracting page N rescans the archive; the caller can cache
 * decoded current/neighbor pages under a separate, bounded image-cache policy.
 */
class CbzArchive private constructor(
    private val openSource: () -> InputStream,
    override val pages: List<ComicPage>,
    private val limits: CbzLimits,
    private val indexedZip: ZipFile? = null,
) : ComicArchive {
    override val pageCount: Int get() = pages.size

    /** Streams one page to [output] while enforcing actual per-entry and aggregate limits. */
    override fun copyPageTo(index: Int, output: OutputStream) {
        val page = pages.getOrNull(index) ?: throw IndexOutOfBoundsException("No page at index $index")
        val zipFile = indexedZip
        if (zipFile != null) {
            try {
                val entry = zipFile.getEntry(page.entryName)
                    ?: throw InvalidComicArchiveException("Indexed page is missing from the archive")
                zipFile.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var expanded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        expanded += count
                        enforceEntryLimit(expanded, limits)
                        output.write(buffer, 0, count)
                    }
                }
                return
            } catch (e: InvalidComicArchiveException) {
                throw e
            } catch (e: ComicArchiveLimitException) {
                throw e
            } catch (e: Exception) {
                throw InvalidComicArchiveException("Could not read comic archive", e)
            }
        }
        try {
            ZipInputStream(BufferedInputStream(openSource())).use { zip ->
                var entries = 0
                var expanded = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > limits.maxEntries) throw ComicArchiveLimitException("Archive has too many entries")
                    validateEntryName(entry.name)
                    val target = entry.name == page.entryName
                    var entryBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        expanded += count
                        enforceEntryLimit(entryBytes, limits)
                        enforceTotalLimit(expanded, limits)
                        if (target) output.write(buffer, 0, count)
                    }
                    zip.closeEntry()
                    if (target) return
                }
                throw InvalidComicArchiveException("Indexed page is missing from the archive")
            }
        } catch (e: InvalidComicArchiveException) {
            throw e
        } catch (e: ComicArchiveLimitException) {
            throw e
        } catch (e: Exception) {
            throw InvalidComicArchiveException("Could not read comic archive", e)
        }
    }

    override fun readPage(index: Int): ByteArray = ByteArrayOutputStream().use { out ->
        copyPageTo(index, out)
        out.toByteArray()
    }

    override fun close() {
        indexedZip?.close()
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
        private const val MAX_PATH_LENGTH = 4_096

        /** Indexes a CBZ without retaining page contents. [openSource] must return a fresh stream. */
        fun open(openSource: () -> InputStream, limits: CbzLimits = CbzLimits()): CbzArchive {
            val found = mutableListOf<Pair<String, Long>>()
            val names = HashSet<String>()
            var entryCount = 0
            var expanded = 0L
            try {
                ZipInputStream(BufferedInputStream(openSource())).use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entryCount++
                        if (entryCount > limits.maxEntries) throw ComicArchiveLimitException("Archive has too many entries")
                        validateEntryName(entry.name)
                        if (!entry.isDirectory && !names.add(entry.name)) {
                            throw InvalidComicArchiveException("Archive contains duplicate entry names")
                        }
                        var entryBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            expanded += count
                            enforceEntryLimit(entryBytes, limits)
                            enforceTotalLimit(expanded, limits)
                        }
                        zip.closeEntry()
                        if (!entry.isDirectory && isPage(entry.name) && entryBytes > 0) {
                            if (found.size >= limits.maxPages) throw ComicArchiveLimitException("Archive has too many page images")
                            found += entry.name to entryBytes
                        }
                    }
                }
            } catch (e: InvalidComicArchiveException) {
                throw e
            } catch (e: ComicArchiveLimitException) {
                throw e
            } catch (e: Exception) {
                throw InvalidComicArchiveException("Could not index comic archive", e)
            }
            if (found.isEmpty()) throw InvalidComicArchiveException("Archive contains no supported page images")
            val sorted = found.sortedWith { a, b -> naturalCompare(a.first, b.first) }
            return CbzArchive(openSource, sorted.mapIndexed { i, item -> ComicPage(i, item.first, item.second) }, limits)
        }

        /** Opens a local, seekable copy so each page can be read without rescanning earlier ZIP entries. */
        fun openFile(file: File, limits: CbzLimits = CbzLimits()): CbzArchive {
            val zip = try {
                ZipFile(file)
            } catch (e: Exception) {
                throw InvalidComicArchiveException("Could not open comic archive", e)
            }
            try {
                val found = mutableListOf<Pair<String, Long>>()
                val names = HashSet<String>()
                val entries = zip.entries()
                var entryCount = 0
                var expanded = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    if (entryCount > limits.maxEntries) throw ComicArchiveLimitException("Archive has too many entries")
                    validateEntryName(entry.name)
                    if (!entry.isDirectory && !names.add(entry.name)) {
                        throw InvalidComicArchiveException("Archive contains duplicate entry names")
                    }
                    var entryBytes = 0L
                    if (!entry.isDirectory) {
                        zip.getInputStream(entry).use { input ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                expanded += count
                                enforceEntryLimit(entryBytes, limits)
                                enforceTotalLimit(expanded, limits)
                            }
                        }
                    }
                    if (!entry.isDirectory && isPage(entry.name) && entryBytes > 0) {
                        if (found.size >= limits.maxPages) throw ComicArchiveLimitException("Archive has too many page images")
                        found += entry.name to entryBytes
                    }
                }
                if (found.isEmpty()) throw InvalidComicArchiveException("Archive contains no supported page images")
                val sorted = found.sortedWith { a, b -> naturalCompare(a.first, b.first) }
                return CbzArchive(
                    openSource = { file.inputStream() },
                    pages = sorted.mapIndexed { i, item -> ComicPage(i, item.first, item.second) },
                    limits = limits,
                    indexedZip = zip,
                )
            } catch (e: InvalidComicArchiveException) {
                zip.close()
                throw e
            } catch (e: ComicArchiveLimitException) {
                zip.close()
                throw e
            } catch (e: Exception) {
                zip.close()
                throw InvalidComicArchiveException("Could not index comic archive", e)
            }
        }

        internal fun isPage(name: String): Boolean {
            val base = name.substringAfterLast('/')
            if (base.startsWith('.') || base.equals("thumbs.db", ignoreCase = true)) return false
            return base.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        }

        internal fun validateEntryName(name: String) {
            if (name.isEmpty() || name.length > MAX_PATH_LENGTH || '\u0000' in name || '\\' in name) {
                throw InvalidComicArchiveException("Archive contains an invalid entry path")
            }
            if (name.startsWith('/') || name.startsWith('~') || Regex("^[A-Za-z]:").containsMatchIn(name)) {
                throw InvalidComicArchiveException("Archive contains an absolute entry path")
            }
            if (name.split('/').any { it == ".." || it == "." }) {
                throw InvalidComicArchiveException("Archive contains a traversal entry path")
            }
        }

        internal fun enforceEntryLimit(bytes: Long, limits: CbzLimits) {
            if (bytes > limits.maxEntryBytes) throw ComicArchiveLimitException("Archive entry expands beyond the allowed size")
        }

        internal fun enforceTotalLimit(bytes: Long, limits: CbzLimits) {
            if (bytes > limits.maxExpandedBytes) throw ComicArchiveLimitException("Archive expands beyond the allowed size")
        }

        internal fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    val startA = i
                    val startB = j
                    while (i < a.length && a[i].isDigit()) i++
                    while (j < b.length && b[j].isDigit()) j++
                    val digitsA = a.substring(startA, i).trimStart('0').ifEmpty { "0" }
                    val digitsB = b.substring(startB, j).trimStart('0').ifEmpty { "0" }
                    if (digitsA.length != digitsB.length) return digitsA.length.compareTo(digitsB.length)
                    val numeric = digitsA.compareTo(digitsB)
                    if (numeric != 0) return numeric
                    val zeroes = (i - startA).compareTo(j - startB)
                    if (zeroes != 0) return zeroes
                } else {
                    val folded = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (folded != 0) return folded
                    if (ca != cb) return ca.compareTo(cb)
                    i++
                    j++
                }
            }
            return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
        }
    }
}
