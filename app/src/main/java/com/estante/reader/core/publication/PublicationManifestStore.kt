package com.estante.reader.core.publication

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PublicationManifestStore {
    private const val MANIFEST_FILE = "estante-publication.json"

    fun save(publication: ImportedPublication) {
        val chapters = JSONArray()
        publication.chapters.forEach { chapter ->
            chapters.put(JSONObject().put("href", chapter.href).put("title", chapter.title).put("estimatedLength", chapter.estimatedLength))
        }
        val coverPath = publication.coverFile?.canonicalFile
            ?.takeIf { it.path.startsWith(publication.root.canonicalPath + File.separator) }
            ?.relativeTo(publication.root.canonicalFile)?.path
        val json = JSONObject()
            .put("version", 3)
            .put("format", publication.formatId)
            .put("title", publication.title)
            .put("author", publication.author)
            .put("contentKind", publication.contentKind.name)
            .put("chapters", chapters)
            .put("cover", coverPath)
            .put("capabilities", JSONObject()
                .put("tableOfContents", publication.capabilities.tableOfContents)
                .put("textSearch", publication.capabilities.textSearch)
                .put("typography", publication.capabilities.typography)
                .put("bookmarks", publication.capabilities.bookmarks)
                .put("columns", publication.capabilities.columns)
                .put("guidedRegions", publication.capabilities.guidedRegions)
                .put("textSelection", publication.capabilities.textSelection)
                .put("highlights", publication.capabilities.highlights)
                .put("readingDirection", publication.capabilities.readingDirection))
        File(publication.root, MANIFEST_FILE).writeText(json.toString(), Charsets.UTF_8)
    }

    fun load(root: File): ImportedPublication? = runCatching {
        val json = JSONObject(File(root, MANIFEST_FILE).readText(Charsets.UTF_8))
        require(json.optInt("version") in 1..3)
        val chaptersJson = json.getJSONArray("chapters")
        val chapters = (0 until chaptersJson.length()).map { index ->
            val chapter = chaptersJson.getJSONObject(index)
            val href = chapter.getString("href")
            val savedLength = chapter.optInt("estimatedLength", 0)
            val migratedLength = if (savedLength > 0) savedLength else runCatching {
                val base = root.canonicalFile
                val content = File(base, href).canonicalFile
                require(content.path.startsWith(base.path + File.separator) && content.isFile)
                org.jsoup.Jsoup.parse(content, "UTF-8").text().length.coerceAtLeast(1)
            }.getOrDefault(1)
            PublicationChapter(href, chapter.getString("title"), migratedLength)
        }
        require(chapters.isNotEmpty())
        val cover = json.optString("cover").takeIf(String::isNotBlank)?.let { relative ->
            val base = root.canonicalFile
            File(base, relative).canonicalFile.takeIf { it.path.startsWith(base.path + File.separator) && it.isFile }
        }
        val capabilityJson = json.optJSONObject("capabilities") ?: JSONObject()
        ImportedPublication(
            formatId = json.getString("format"),
            title = json.getString("title"),
            author = json.optString("author").takeIf(String::isNotBlank),
            chapters = chapters,
            root = root,
            coverFile = cover,
            contentKind = runCatching { PublicationContentKind.valueOf(json.optString("contentKind")) }
                .getOrDefault(PublicationContentKind.REFLOWABLE_TEXT),
            capabilities = PublicationCapabilities(
                tableOfContents = capabilityJson.optBoolean("tableOfContents", true),
                textSearch = capabilityJson.optBoolean("textSearch", true),
                typography = capabilityJson.optBoolean("typography", true),
                bookmarks = capabilityJson.optBoolean("bookmarks", true),
                columns = json.getString("format") == "MOBI" || capabilityJson.optBoolean("columns", json.getString("format") == "EPUB"),
                guidedRegions = capabilityJson.optBoolean("guidedRegions", false),
                textSelection = capabilityJson.optBoolean("textSelection", true),
                highlights = capabilityJson.optBoolean("highlights", true),
                readingDirection = capabilityJson.optBoolean("readingDirection", true),
            ),
        )
    }.getOrNull()
}
