package com.estante.reader.core.publication

import java.io.File
import java.net.URLDecoder
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Finds the cover inside an extracted publication without trusting paths outside its root. */
internal object PublicationCoverResolver {
    fun find(root: File): File? = runCatching {
        val base = root.canonicalFile
        fun local(parent: File, href: String): File? {
            val path = URLDecoder.decode(href.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
            val file = File(parent, path).canonicalFile
            return file.takeIf { it.path.startsWith(base.path + File.separator) && it.isFile && it.length() in 1..24L * 1024 * 1024 }
        }
        val container = File(base, "META-INF/container.xml")
        val descriptor = Jsoup.parse(container, null, "", Parser.xmlParser())
        val opfPath = descriptor.getAllElements().firstOrNull { it.tagName().substringAfter(':') == "rootfile" }?.attr("full-path") ?: return@runCatching null
        val opfFile = local(base, opfPath) ?: return@runCatching null
        val opf = Jsoup.parse(opfFile, null, "", Parser.xmlParser())
        val elements = opf.getAllElements()
        val items = elements.filter { it.tagName().substringAfter(':') == "item" }
        val coverId = elements.firstOrNull { it.tagName().substringAfter(':') == "meta" && it.attr("name") == "cover" }?.attr("content")
        val image = items.firstOrNull { it.attr("properties").split(Regex("\\s+")).contains("cover-image") }
            ?: items.firstOrNull { it.attr("id") == coverId }
        image?.let { local(opfFile.parentFile!!, it.attr("href")) }?.let { return@runCatching it }
        val coverPage = elements.firstOrNull { it.tagName().substringAfter(':') == "reference" && it.attr("type") == "cover" }?.attr("href")
            ?: items.firstOrNull { it.attr("href").contains("cover", true) && it.attr("media-type").contains("html") }?.attr("href")
        val page = coverPage?.let { local(opfFile.parentFile!!, it) } ?: return@runCatching null
        val document = Jsoup.parse(page, null)
        val href = document.selectFirst("img[src]")?.attr("src")
            ?: document.selectFirst("image")?.let { it.attr("href").ifBlank { it.attr("xlink:href") } }
        href?.let { local(page.parentFile!!, it) }
    }.getOrNull()
}
