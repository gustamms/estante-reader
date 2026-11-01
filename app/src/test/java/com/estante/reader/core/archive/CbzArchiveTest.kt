package com.estante.reader.core.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CbzArchiveTest {
    @Test
    fun `pages are naturally ordered and metadata is ignored`() {
        val archive = openZip(
            "pages/10.jpg" to bytes("ten"),
            "pages/2.jpg" to bytes("two"),
            "ComicInfo.xml" to bytes("metadata"),
            "__MACOSX/._1.jpg" to bytes("resource fork"),
            "pages/.hidden.jpg" to bytes("hidden"),
        )

        assertEquals(listOf("pages/2.jpg", "pages/10.jpg"), archive.pages.map { it.entryName })
        assertEquals(2, archive.pageCount)
    }

    @Test
    fun `selected page can be streamed without returning neighboring pages`() {
        val content = openZip("1.jpg" to bytes("first"), "2.png" to bytes("second"))
        val output = ByteArrayOutputStream()

        content.copyPageTo(1, output)

        assertArrayEquals(bytes("second"), output.toByteArray())
    }

    @Test
    fun `natural ordering handles numbers with different digit lengths`() {
        val archive = openZip("page12.jpg" to bytes("12"), "page003.jpg" to bytes("3"), "page1.jpg" to bytes("1"))

        assertEquals(listOf("page1.jpg", "page003.jpg", "page12.jpg"), archive.pages.map { it.entryName })
    }

    @Test
    fun `rejects traversal path`() {
        val zip = zip("../outside.jpg" to bytes("bad"))

        assertThrows(InvalidComicArchiveException::class.java) { index(zip) }
    }

    @Test
    fun `rejects absolute paths`() {
        val zip = zip("/outside.jpg" to bytes("bad"))

        assertThrows(InvalidComicArchiveException::class.java) { index(zip) }
    }

    @Test
    fun `rejects an archive that expands past a per entry limit`() {
        val zip = zip("1.jpg" to ByteArray(9))

        assertThrows(ComicArchiveLimitException::class.java) {
            index(zip, CbzLimits(maxEntryBytes = 8, maxExpandedBytes = 8))
        }
    }

    @Test
    fun `aggregate expansion limit includes non image entries`() {
        val zip = zip("metadata.txt" to ByteArray(5), "1.jpg" to ByteArray(5))

        assertThrows(ComicArchiveLimitException::class.java) {
            index(zip, CbzLimits(maxEntryBytes = 8, maxExpandedBytes = 8))
        }
    }

    @Test
    fun `rejects too many ZIP entries`() {
        val zip = zip("metadata.txt" to bytes("x"), "1.jpg" to bytes("y"))

        assertThrows(ComicArchiveLimitException::class.java) {
            index(zip, CbzLimits(maxEntries = 1, maxPages = 1, maxEntryBytes = 16, maxExpandedBytes = 16))
        }
    }

    @Test
    fun `rejects archives with no supported image pages`() {
        val zip = zip("ComicInfo.xml" to bytes("metadata"))

        assertThrows(InvalidComicArchiveException::class.java) { index(zip) }
    }

    @Test
    fun `rejects malformed ZIP data`() {
        assertThrows(InvalidComicArchiveException::class.java) { index(bytes("not a zip")) }
    }

    @Test
    fun `rejects page index out of bounds`() {
        val archive = openZip("1.jpg" to bytes("page"))

        assertThrows(IndexOutOfBoundsException::class.java) { archive.readPage(1) }
    }

    private fun openZip(vararg entries: Pair<String, ByteArray>) = index(zip(*entries))

    private fun index(bytes: ByteArray, limits: CbzLimits = CbzLimits()) =
        CbzArchive.open(openSource = { ByteArrayInputStream(bytes) }, limits = limits)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)
}
