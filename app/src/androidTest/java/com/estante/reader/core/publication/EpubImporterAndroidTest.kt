package com.estante.reader.core.publication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpubImporterAndroidTest {
    @Test
    fun importsEpubUsingAndroidXmlParser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val epub = File.createTempFile("epub-parser-", ".epub", context.cacheDir)
        val destination = File(context.cacheDir, "epub-parser-output-${System.nanoTime()}")

        try {
            createEpub(epub)

            val publication = EpubImporter().import(epub, destination, "Fallback title")

            assertEquals("EPUB", publication.formatId)
            assertEquals("Parser regression fixture", publication.title)
            assertEquals(1, publication.chapters.size)
            assertTrue(File(publication.root, publication.chapters.single().href).isFile)
        } finally {
            destination.deleteRecursively()
            epub.delete()
        }
    }

    private fun createEpub(file: File) {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent(),
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">urn:example:parser-regression</dc:identifier>
                    <dc:title>Parser regression fixture</dc:title>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>""".trimIndent(),
            "OEBPS/nav.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Chapter One</a></li></ol></nav></body>
                </html>""".trimIndent(),
            "OEBPS/chapter.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter One</title></head>
                  <body><p>This EPUB checks the Android XML parser.</p></body>
                </html>""".trimIndent(),
        )

        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    val crc = CRC32().apply { update(bytes) }
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
