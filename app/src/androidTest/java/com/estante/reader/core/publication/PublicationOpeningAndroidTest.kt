package com.estante.reader.core.publication

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationOpeningAndroidTest {
    @Test
    fun importedEpubAndMobiChaptersRenderAfterManifestReload() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val work = File(context.cacheDir, "publication-opening-${System.nanoTime()}").apply { mkdirs() }
        try {
            val epubXhtml = File(work, "xhtml.epub").apply { writeEpub(this, "chapter.xhtml", XHTML_WITH_VOID_ELEMENTS) }
            val epubHtml = File(work, "html.epub").apply { writeEpub(this, "chapter.html", HTML_CHAPTER) }
            val mobiPlain = File(work, "plain.mobi").apply { writeMobi(this, HTML_WITH_ACCENTS, compression = 1, encoding = 65001) }
            val mobiPalmDoc = File(work, "palmdoc.mobi").apply { writeMobi(this, HTML_CHAPTER, compression = 2, encoding = 65001) }
            val cases = listOf(
                Triple("epub-xhtml", epubXhtml, EpubImporter()),
                Triple("epub-html", epubHtml, EpubImporter()),
                Triple("mobi-plain", mobiPlain, MobiImporter()),
                Triple("mobi-palmdoc", mobiPalmDoc, MobiImporter()),
            )

            cases.forEach { (name, source, importer) ->
                val root = File(work, "$name-imported")
                val imported = importer.import(source, root, name)
                PublicationManifestStore.save(imported)
                val reopened = requireNotNull(PublicationManifestStore.load(root))
                val chapter = reopened.chapters.single()
                val chapterFile = File(root, chapter.href)
                val mimeType = PublicationResourceMimeType.resolve(chapter.href, reopened.chapters)

                assertEquals("text/html", mimeType)
                assertTrue("$name chapter missing", chapterFile.isFile)
                val rendered = renderChapter(instrumentation, chapterFile.readText(Charsets.UTF_8), mimeType)
                assertTrue("$name did not show chapter text: $rendered", rendered.contains("VISIBLE_MARKER"))
                assertTrue("$name produced XML error UI: $rendered", !rendered.contains("This XML file"))
            }

            assertEquals("image/png", PublicationResourceMimeType.resolve("Images/cover.png", emptyList()))
            assertEquals("text/css", PublicationResourceMimeType.resolve("Styles/book.css", emptyList()))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun renderChapter(
        instrumentation: android.app.Instrumentation,
        html: String,
        mimeType: String,
    ): String {
        val completed = CountDownLatch(1)
        var result = ""
        var webView: WebView? = null
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "JSON.stringify({text:document.body?.innerText||'NO_BODY',parserErrors:document.getElementsByTagName('parsererror').length})",
                        ) { value -> result = value.orEmpty(); completed.countDown() }
                    }
                }
                loadDataWithBaseURL("https://reader.estante.local/book/test/chapter.xhtml", html, mimeType, "UTF-8", null)
            }
        }
        try {
            assertTrue("WebView did not finish rendering", completed.await(20, TimeUnit.SECONDS))
            return result
        } finally {
            instrumentation.runOnMainSync { webView?.destroy() }
        }
    }

    private fun writeEpub(file: File, chapterName: String, chapter: String) {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>",
            "book.opf" to "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Sample</dc:title></metadata><manifest><item id=\"chapter\" href=\"$chapterName\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"chapter\"/></spine></package>",
            chapterName to chapter,
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun writeMobi(file: File, html: String, compression: Int, encoding: Int) {
        val text = html.toByteArray(if (encoding == 65001) Charsets.UTF_8 else Charsets.ISO_8859_1)
        val output = ByteArray(134 + text.size)
        val buffer = ByteBuffer.wrap(output)
        buffer.position(60)
        buffer.put("BOOKMOBI".toByteArray(Charsets.US_ASCII))
        buffer.putShort(76, 2)
        buffer.putInt(78, 94)
        buffer.putInt(86, 134)
        buffer.putShort(94, compression.toShort())
        buffer.putInt(98, text.size)
        buffer.putShort(102, 1)
        buffer.position(110)
        buffer.put("MOBI".toByteArray(Charsets.US_ASCII))
        buffer.putInt(122, encoding)
        buffer.putInt(130, 6)
        buffer.position(134)
        buffer.put(text)
        file.writeBytes(output)
    }

    private companion object {
        const val HTML_CHAPTER = "<html><body><p>VISIBLE_MARKER PalmDOC sample</p></body></html>"
        const val HTML_WITH_ACCENTS = "<html><body><p>VISIBLE_MARKER ação e coração</p></body></html>"
        const val XHTML_WITH_VOID_ELEMENTS = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="utf-8"/></head><body><p>VISIBLE_MARKER capítulo</p><br/></body></html>"""
    }
}
