package com.estante.reader.core.publication

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.jsoup.nodes.Element
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipFile

class EpubImporter : PublicationImporter {
    override val formatId: String = "EPUB"

    override fun recognizes(file: File, displayName: String): Boolean =
        file.extension.equals("epub", ignoreCase = true) || file.hasZipSignature() && runCatching {
            ZipFile(file).use { it.getEntry("META-INF/container.xml") != null }
        }.getOrDefault(false)

    override fun import(file: File, destination: File, fallbackTitle: String): ImportedPublication {
        destination.deleteRecursively()
        destination.parentFile?.let { if (!it.exists()) it.mkdirs() }
        require(destination.mkdirs()) { "Não foi possível preparar o livro." }
        try {
            ZipFile(file).use { zip ->
                extractSafely(zip, destination)
                val container = zip.getEntry("META-INF/container.xml")
                    ?: throw UnsupportedPublicationException("Este EPUB não contém o índice de publicação.")
                val rootPath = parseXml(zip.getInputStream(container).use { it.readBytes() })
                    .elements("rootfile").firstOrNull()?.attr("full-path")
                    ?.takeIf(String::isNotBlank)
                    ?: throw UnsupportedPublicationException("Não foi possível localizar a estrutura do EPUB.")
                val opfFile = safeFile(destination, rootPath)
                val opf = parseXml(opfFile.readBytes())
                val opfDirectory = rootPath.substringBeforeLast('/', "")
                val manifest = opf.elements("item").associateBy { it.attr("id") }
                if (manifest.isEmpty()) throw UnsupportedPublicationException("O EPUB não contém capítulos legíveis.")

                val metadata = opf.elements("metadata").firstOrNull()
                val layout = metadata?.elements("meta")?.firstOrNull { it.attr("property") == "rendition:layout" }
                    ?.text()?.trim()?.lowercase(Locale.ROOT)
                if (layout == "pre-paginated") {
                    throw UnsupportedPublicationException("Este EPUB usa layout fixo. No momento, o leitor aceita livros de texto fluido.")
                }
                zip.getEntry("META-INF/encryption.xml")?.let { entry ->
                    val encrypted = parseXml(zip.getInputStream(entry).use { it.readBytes() }).elements("EncryptionMethod")
                    val unsupported = encrypted.any { method ->
                        method.attr("Algorithm") !in setOf(
                            "http://www.idpf.org/2008/embedding",
                            "http://ns.adobe.com/pdf/enc#RC",
                        )
                    }
                    if (unsupported) throw UnsupportedPublicationException("Este EPUB parece protegido por DRM e não pode ser aberto.")
                }
                val title = metadata?.elements("title")?.firstOrNull()?.text()?.trim()
                    ?.takeIf(String::isNotBlank) ?: fallbackTitle
                val author = metadata?.elements("creator")?.firstOrNull()?.text()?.trim()
                    ?.takeIf(String::isNotBlank)

                val navItem = manifest.values.firstOrNull { item ->
                    item.attr("properties").split(Regex("\\s+")).contains("nav")
                }
                val epub3Titles = navItem?.let { item ->
                    val navPath = joinPath(opfDirectory, item.attr("href"))
                    val navFile = safeFile(destination, navPath)
                    if (navFile.isFile) {
                        Jsoup.parse(navFile, "UTF-8").select("nav a[href]")
                            .filter { anchor -> anchor.attr("epub:type").isNotBlank() || anchor.parents().any { it.normalName() == "nav" } }
                            .associate { anchor ->
                                joinPath(navPath.substringBeforeLast('/', ""), anchor.attr("href").substringBefore('#')) to anchor.text().trim()
                            }
                    } else emptyMap()
                }.orEmpty()
                val spineElement = opf.elements("spine").firstOrNull()
                val epub2TocItem = spineElement?.attr("toc")?.let(manifest::get)
                val epub2Titles = epub2TocItem?.let { item ->
                    val ncxPath = joinPath(opfDirectory, item.attr("href"))
                    val ncxFile = safeFile(destination, ncxPath)
                    if (ncxFile.isFile) {
                        parseXml(ncxFile.readBytes()).elements("navPoint").mapNotNull { point ->
                            val href = point.elements("content").firstOrNull()?.attr("src")
                                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                            val label = point.elements("navLabel").firstOrNull()?.elements("text")?.firstOrNull()
                                ?.text()?.trim().orEmpty()
                            joinPath(ncxPath.substringBeforeLast('/', ""), href) to label
                        }.toMap()
                    } else emptyMap()
                }.orEmpty()
                val tocTitles = epub3Titles + epub2Titles

                val spine = opf.elements("itemref").mapNotNull { itemref ->
                    manifest[itemref.attr("idref")]
                }
                val chapters = spine.mapNotNull { item ->
                    val href = joinPath(opfDirectory, item.attr("href").substringBefore('#'))
                    val chapterFile = safeFile(destination, href)
                    if (!chapterFile.isFile || chapterFile.length() > MAX_CHAPTER_BYTES) return@mapNotNull null
                    val sanitized = sanitizeHtml(chapterFile.readText(Charsets.UTF_8))
                    chapterFile.writeText(sanitized, Charsets.UTF_8)
                    PublicationChapter(href, tocTitles[href]?.takeIf(String::isNotBlank)
                        ?: titleFromHref(href).ifBlank { title })
                }
                if (chapters.isEmpty()) throw UnsupportedPublicationException("Este EPUB não tem capítulos de texto compatíveis.")

                val coverItem = manifest.values.firstOrNull { it.attr("properties").split(Regex("\\s+")).contains("cover-image") }
                    ?: metadata?.elements("meta")?.firstOrNull { it.attr("name") == "cover" }
                        ?.let { meta -> manifest[meta.attr("content")] }
                val cover = coverItem?.let { safeFile(destination, joinPath(opfDirectory, it.attr("href"))) }
                    ?.takeIf { it.isFile && it.length() in 1..MAX_COVER_BYTES }

                return ImportedPublication(formatId, title, author, chapters, destination, cover)
            }
        } catch (error: UnsupportedPublicationException) {
            destination.deleteRecursively()
            throw error
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw UnsupportedPublicationException("Não foi possível ler este EPUB: ${error.message ?: "arquivo inválido"}.")
        }
    }

    private fun extractSafely(zip: ZipFile, destination: File) {
        val root = destination.canonicalFile
        var extractedBytes = 0L
        var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            count++
            if (count > MAX_ARCHIVE_ENTRIES) throw UnsupportedPublicationException("O EPUB contém arquivos demais.")
            if (entry.size > MAX_ARCHIVE_BYTES || entry.size > 0 && extractedBytes + entry.size > MAX_ARCHIVE_BYTES) {
                throw UnsupportedPublicationException("O conteúdo descompactado do EPUB excede o limite permitido.")
            }
            val output = safeFile(destination, entry.name)
            output.parentFile?.let { parent -> if (!parent.exists() && !parent.mkdirs()) error("Falha ao extrair o EPUB.") }
            zip.getInputStream(entry).use { input ->
                FileOutputStream(output).use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        extractedBytes += read
                        if (extractedBytes > MAX_ARCHIVE_BYTES) {
                            throw UnsupportedPublicationException("O conteúdo descompactado do EPUB excede o limite permitido.")
                        }
                        stream.write(buffer, 0, read)
                    }
                }
            }
            check(output.canonicalPath.startsWith(root.path + File.separator))
        }
    }

    /**
     * Jsoup's XML parser is available on Android and does not resolve external
     * entities. JAXP's Android implementation rejects the security features this
     * importer needs to configure, so using it makes valid EPUB XML fail to parse.
     */
    private fun parseXml(bytes: ByteArray): org.jsoup.nodes.Document =
        Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())

    private fun org.jsoup.nodes.Document.elements(name: String) =
        getAllElements().filter { it !== this && it.tagName().substringAfter(':').equals(name, ignoreCase = true) }

    private fun Element.elements(name: String) =
        getAllElements().filter { it !== this && it.tagName().substringAfter(':').equals(name, ignoreCase = true) }

    private fun joinPath(base: String, href: String): String {
        val cleanHref = href.substringBefore('?').substringBefore('#').replace('\\', '/')
        val parts = (if (cleanHref.startsWith('/')) cleanHref.drop(1) else "$base/$cleanHref")
            .split('/').filter { it.isNotBlank() }
        val normalized = ArrayDeque<String>()
        parts.forEach { part ->
            when (part) {
                "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeLast() else error("Caminho inválido no EPUB.")
                else -> normalized.addLast(java.net.URLDecoder.decode(part.replace("+", "%2B"), "UTF-8"))
            }
        }
        return normalized.joinToString("/")
    }

    private fun safeFile(root: File, relativePath: String): File {
        val base = root.canonicalFile
        val candidate = File(base, relativePath).canonicalFile
        if (!candidate.path.startsWith(base.path + File.separator)) error("Caminho de arquivo inválido no EPUB.")
        return candidate
    }

    private fun titleFromHref(href: String) = href.substringAfterLast('/').substringBeforeLast('.')
        .replace(Regex("[-_]"), " ").replaceFirstChar { it.titlecase(Locale.getDefault()) }

    companion object {
        internal fun sanitizeHtml(source: String): String {
            val document = Jsoup.parse(source)
            document.select("script, iframe, frame, object, embed, form, input, button, base, meta[http-equiv]").remove()
            document.select("*").forEach { element ->
                element.attributes().asList().map { it.key }.forEach { key ->
                    if (key.startsWith("on", ignoreCase = true) || key.equals("srcdoc", ignoreCase = true)) {
                        element.removeAttr(key)
                    }
                }
                listOf("href", "src", "poster", "xlink:href").forEach { key ->
                    val value = element.attr(key).trim()
                    if (value.startsWith("javascript:", true) || value.startsWith("file:", true) || value.startsWith("data:", true)) {
                        element.removeAttr(key)
                    }
                }
            }
            document.outputSettings().charset(Charsets.UTF_8).escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
            return document.outerHtml()
        }

        private const val MAX_ARCHIVE_BYTES = 768L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 30_000
        private const val MAX_CHAPTER_BYTES = 16L * 1024 * 1024
        private const val MAX_COVER_BYTES = 24L * 1024 * 1024
    }
}

class MobiImporter : PublicationImporter {
    override val formatId: String = "MOBI"

    override fun recognizes(file: File, displayName: String): Boolean =
        file.extension.equals("mobi", ignoreCase = true) || file.extension.equals("prc", ignoreCase = true) || file.hasPalmDatabaseSignature()

    override fun import(file: File, destination: File, fallbackTitle: String): ImportedPublication {
        destination.deleteRecursively()
        destination.parentFile?.let { if (!it.exists()) it.mkdirs() }
        require(destination.mkdirs()) { "Não foi possível preparar o livro." }
        try {
            val decoded = decodePalmDoc(file)
            val chapter = File(destination, "chapter-000.xhtml")
            chapter.writeText(EpubImporter.sanitizeHtml(decoded), Charsets.UTF_8)
            val title = fallbackTitle.ifBlank { file.nameWithoutExtension.ifBlank { "Livro MOBI" } }
            return ImportedPublication(formatId, title, null, listOf(PublicationChapter(chapter.name, title)), destination, null,
                capabilities = PublicationCapabilities(typography = true, textSearch = true, bookmarks = true))
        } catch (error: UnsupportedPublicationException) {
            destination.deleteRecursively()
            throw error
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw UnsupportedPublicationException("Não foi possível ler este MOBI: ${error.message ?: "arquivo inválido"}.")
        }
    }

    private fun decodePalmDoc(file: File): String {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 86) throw UnsupportedPublicationException("Este arquivo MOBI está incompleto.")
            val firstRecord = input.readPalmRecords(maxRecords = 1, maxTotalBytes = MAX_RECORD_BYTES).firstOrNull()
                ?: throw UnsupportedPublicationException("Este arquivo MOBI não contém registros de texto.")
            if (firstRecord.size < 24 || firstRecord.copyOfRange(16, 20).toString(Charsets.US_ASCII) != "MOBI") {
                throw UnsupportedPublicationException("O arquivo não é um livro MOBI válido.")
            }
            val compression = firstRecord.u16(0)
            val textLength = firstRecord.u32(4)
            val textRecordCount = firstRecord.u16(8)
            val encryption = firstRecord.u16(12)
            val encoding = if (firstRecord.size >= 32) firstRecord.u32(28).toInt() else 1252
            val mobiVersion = if (firstRecord.size >= 40) firstRecord.u32(36).toInt() else 6
            if (encryption != 0) throw UnsupportedPublicationException("Este MOBI tem proteção DRM e não pode ser aberto.")
            if (compression == 17480 || mobiVersion >= 8) {
                throw UnsupportedPublicationException("Este arquivo usa o formato Kindle KF8/HUFF. Esta versão aceita MOBI clássico sem DRM.")
            }
            if (compression != 1 && compression != 2) {
                throw UnsupportedPublicationException("A compressão deste MOBI ainda não é compatível.")
            }
            if (textRecordCount == 0 || textRecordCount > 20_000 || textLength > MAX_TEXT_BYTES) {
                throw UnsupportedPublicationException("O conteúdo deste MOBI excede os limites de leitura.")
            }
            val records = input.readPalmRecords(textRecordCount + 1, MAX_TEXT_BYTES)
            if (records.size < textRecordCount + 1) throw UnsupportedPublicationException("O MOBI não contém todos os registros de texto.")
            val text = java.io.ByteArrayOutputStream(minOf(textLength.toInt(), 4 * 1024 * 1024))
            for (index in 1..textRecordCount) {
                val data = if (compression == 1) records[index] else decompressPalmDoc(records[index])
                if (text.size() + data.size > MAX_TEXT_BYTES) throw UnsupportedPublicationException("O texto deste MOBI excede o limite permitido.")
                text.write(data)
            }
            val charset = runCatching {
                when (encoding) {
                    65001 -> Charsets.UTF_8
                    1252 -> Charset.forName("windows-1252")
                    else -> Charset.forName("windows-$encoding")
                }
            }.getOrDefault(Charsets.UTF_8)
            val result = text.toByteArray().toString(charset).trim('\u0000', '\u0004', '\u0007', '\u0008', '\u000c', '\u001a', ' ', '\n', '\r')
            if (result.isBlank()) throw UnsupportedPublicationException("Este MOBI não possui texto legível.")
            return result
        }
    }

    private fun RandomAccessFile.readPalmRecords(
        maxRecords: Int = Int.MAX_VALUE,
        maxTotalBytes: Long = Long.MAX_VALUE,
    ): List<ByteArray> {
        seek(76)
        val countBytes = ByteArray(2).also(::readFully)
        val count = countBytes.u16(0)
        if (count == 0 || count > 20_001) throw UnsupportedPublicationException("A estrutura deste MOBI não é válida.")
        val table = ByteArray(count * 8).also(::readFully)
        val offsets = (0 until count).map { index -> table.u32(index * 8) }
        var totalBytes = 0L
        return offsets.take(maxRecords).mapIndexed { index, offset ->
            val next = offsets.getOrNull(index + 1) ?: length()
            if (offset > next || next > length() || next - offset > MAX_RECORD_BYTES) {
                throw UnsupportedPublicationException("Um registro deste MOBI excede os limites permitidos.")
            }
            totalBytes += next - offset
            if (totalBytes > maxTotalBytes) throw UnsupportedPublicationException("Os registros deste MOBI excedem os limites permitidos.")
            seek(offset)
            ByteArray((next - offset).toInt()).also(::readFully)
        }
    }

    private fun decompressPalmDoc(input: ByteArray): ByteArray {
        var output = ByteArray((input.size * 2).coerceAtLeast(256).coerceAtMost(MAX_TEXT_BYTES.toInt()))
        var outputSize = 0
        fun ensureSpace(count: Int) {
            if (outputSize + count > MAX_TEXT_BYTES) throw UnsupportedPublicationException("O texto deste MOBI excede o limite permitido.")
            if (outputSize + count > output.size) {
                var capacity = output.size
                while (capacity < outputSize + count) capacity = (capacity * 2).coerceAtMost(MAX_TEXT_BYTES.toInt())
                output = output.copyOf(capacity)
            }
        }
        fun write(value: Int) {
            ensureSpace(1)
            output[outputSize++] = value.toByte()
        }
        var index = 0
        while (index < input.size) {
            val value = input[index++].toInt() and 0xff
            when {
                value == 0 -> write(0)
                value in 1..8 -> {
                    if (index + value > input.size) throw UnsupportedPublicationException("Um registro MOBI está truncado.")
                    ensureSpace(value)
                    input.copyInto(output, outputSize, index, index + value)
                    outputSize += value
                    index += value
                }
                value in 0x09..0x7f -> write(value)
                value in 0x80..0xbf -> {
                    if (index >= input.size) throw UnsupportedPublicationException("Um registro MOBI está truncado.")
                    val pair = (value shl 8) or (input[index++].toInt() and 0xff)
                    val distance = (pair ushr 3) and 0x7ff
                    val length = (pair and 0x7) + 3
                    if (distance == 0 || distance > outputSize) throw UnsupportedPublicationException("A compressão deste MOBI é inválida.")
                    ensureSpace(length)
                    repeat(length) {
                        output[outputSize] = output[outputSize - distance]
                        outputSize++
                    }
                }
                else -> {
                    write(0x20)
                    write(value xor 0x80)
                }
            }
        }
        return output.copyOf(outputSize)
    }

    private fun ByteArray.u16(offset: Int): Int = ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
    private fun ByteArray.u32(offset: Int): Long = ((this[offset].toLong() and 0xff) shl 24) or
        ((this[offset + 1].toLong() and 0xff) shl 16) or ((this[offset + 2].toLong() and 0xff) shl 8) or
        (this[offset + 3].toLong() and 0xff)

    companion object {
        private const val MAX_RECORD_BYTES = 32L * 1024 * 1024
        private const val MAX_TEXT_BYTES = 64L * 1024 * 1024
    }
}

private fun File.hasZipSignature(): Boolean = runCatching {
    FileInputStream(this).use { stream ->
        val bytes = ByteArray(4)
        stream.read(bytes) == 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] in setOf(3.toByte(), 5.toByte(), 7.toByte())
    }
}.getOrDefault(false)

private fun File.hasPalmDatabaseSignature(): Boolean = runCatching {
    RandomAccessFile(this, "r").use { input ->
        if (input.length() < 64) return@use false
        input.seek(60)
        val signature = ByteArray(8).also(input::readFully).toString(Charsets.US_ASCII)
        signature == "BOOKMOBI" || signature == "TEXtREAd"
    }
}.getOrDefault(false)
