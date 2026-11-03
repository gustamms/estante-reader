package com.estante.reader.core.publication

import java.io.File
import java.net.URLConnection
import java.util.Locale

enum class PublicationContentKind { PAGED, REFLOWABLE_TEXT }
enum class BookColumnMode(val id: String) {
    AUTOMATIC("automatic"), SINGLE("single"), DOUBLE("double");

    companion object {
        fun fromId(id: String?): BookColumnMode = entries.firstOrNull { it.id == id } ?: AUTOMATIC
    }
}

data class PublicationCapabilities(
    val tableOfContents: Boolean = false,
    val textSearch: Boolean = false,
    val typography: Boolean = false,
    val bookmarks: Boolean = false,
    val columns: Boolean = false,
    val guidedRegions: Boolean = false,
    val textSelection: Boolean = true,
    val highlights: Boolean = true,
    val readingDirection: Boolean = true,
)

data class PublicationChapter(val href: String, val title: String, val estimatedLength: Int = 1)

internal object PublicationResourceMimeType {
    fun resolve(relativePath: String, chapters: List<PublicationChapter>): String {
        if (chapters.any { it.href == relativePath }) return "text/html"

        val extension = relativePath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "html", "htm", "xhtml" -> "text/html"
            "opf", "ncx", "xml" -> "application/xml"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "woff", "woff2", "ttf", "otf" -> "font/$extension"
            else -> URLConnection.guessContentTypeFromName(relativePath) ?: "application/octet-stream"
        }
    }
}

data class PublicationBookmark(val chapterIndex: Int, val progression: Float, val title: String)
data class PublicationSearchResult(val chapterIndex: Int, val progression: Float, val title: String, val snippet: String)

data class ImportedPublication(
    val formatId: String,
    val title: String,
    val author: String?,
    val chapters: List<PublicationChapter>,
    val root: File,
    val coverFile: File?,
    val contentKind: PublicationContentKind = PublicationContentKind.REFLOWABLE_TEXT,
    val capabilities: PublicationCapabilities = PublicationCapabilities(
        tableOfContents = true,
        textSearch = true,
        typography = true,
        bookmarks = true,
        columns = true,
    ),
)

interface PublicationImporter {
    val formatId: String
    fun recognizes(file: File, displayName: String): Boolean
    fun import(file: File, destination: File, fallbackTitle: String): ImportedPublication
}

class PublicationImportRegistry(
    private val importers: List<PublicationImporter> = listOf(EpubImporter(), MobiImporter()),
) {
    fun importerFor(file: File, displayName: String): PublicationImporter? =
        importers.firstOrNull { importer -> importer.recognizes(file, displayName) }

    fun import(file: File, destination: File, fallbackTitle: String): ImportedPublication {
        val importer = importerFor(file, file.name)
            ?: throw UnsupportedPublicationException("Este formato de livro ainda não é compatível.")
        return importer.import(file, destination, fallbackTitle)
    }
}

class UnsupportedPublicationException(message: String) : IllegalArgumentException(message)
