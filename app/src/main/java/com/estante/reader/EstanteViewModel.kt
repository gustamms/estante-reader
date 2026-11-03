package com.estante.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.estante.reader.core.archive.OpenedPageDocument
import com.estante.reader.core.archive.PdfPageDocument
import com.estante.reader.core.archive.PageDocument
import com.estante.reader.core.archive.PageDocumentFactory
import com.estante.reader.core.analysis.OrtBubbleDetector
import com.estante.reader.core.data.ComicDatabase
import com.estante.reader.core.data.ComicEntity
import com.estante.reader.core.data.ComicFormat
import com.estante.reader.core.data.ComicImportState
import com.estante.reader.core.data.ComicLibraryRepository
import com.estante.reader.core.data.BookHighlightEntity
import com.estante.reader.core.data.BookHighlightRepository
import com.estante.reader.core.guided.GuidedRegion
import com.estante.reader.core.guided.GuidedPage
import com.estante.reader.core.guided.NormalizedRect
import com.estante.reader.core.guided.PageAnalysisStore
import com.estante.reader.core.guided.ReadingDirection
import com.estante.reader.core.reader.PageDisplayMode
import com.estante.reader.core.reader.ReaderSpread
import com.estante.reader.core.reader.ReaderSpreadBuilder
import com.estante.reader.core.publication.ImportedPublication
import com.estante.reader.core.publication.PublicationChapter
import com.estante.reader.core.publication.PublicationBookmark
import com.estante.reader.core.publication.PublicationSearchResult
import com.estante.reader.core.publication.BookColumnMode
import com.estante.reader.core.publication.PublicationImportRegistry
import com.estante.reader.core.publication.PublicationManifestStore
import com.estante.reader.core.publication.PublicationCapabilities
import com.estante.reader.core.publication.BookHighlightColor
import com.estante.reader.core.publication.PublicationImporter
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Application.readerPreferences by preferencesDataStore(name = "reader_preferences")
private val LastDocumentUri = stringPreferencesKey("last_document_uri")
private val LastPageIndex = intPreferencesKey("last_page_index")
private val LastRegionIndex = intPreferencesKey("last_region_index")
private val ReadingDirectionPreference = stringPreferencesKey("reading_direction")
private val PageDisplayModePreference = stringPreferencesKey("page_display_mode")
private val SeparateCoverPreference = booleanPreferencesKey("separate_cover")
private val PanoramasAlonePreference = booleanPreferencesKey("panoramas_alone")
private val LibraryStylePreference = stringPreferencesKey("library_style")
private val ReaderCanvasBackgroundPreference = stringPreferencesKey("reader_canvas_background")
private val GuidedModePreference = booleanPreferencesKey("guided_mode_enabled")
private const val GuidedModeDocumentPrefix = "guided_mode_document_"
private val BookLocationPrefix = "book_location_"
private val BookFontSizePrefix = "book_font_size_"
private val BookColumnModePrefix = "book_column_mode_"
private val BookLineSpacingPrefix = "book_line_spacing_"
private val BookMarginsPrefix = "book_margins_"
private val BookmarksPrefix = "bookmarks_"
private val BookHighlightColorPrefix = "book_highlight_color_"
private const val MIN_HIGHLIGHT_TEXT_LENGTH = 2
private const val MAX_HIGHLIGHT_TEXT_LENGTH = 5000
private const val HIGHLIGHT_CONTEXT_LENGTH = 80

private data class MaterializedArchive(val file: File, val fingerprint: String)

data class ReaderSpreadImages(
    val spread: ReaderSpread,
    val firstBitmap: Bitmap,
    val secondBitmap: Bitmap?,
)

data class ReaderUiState(
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val title: String? = null,
    val documentFormatId: String? = null,
    val lastReadTitle: String? = null,
    val lastReadPage: Int = 0,
    val pageCount: Int = 0,
    val pageIndex: Int = 0,
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val pageDisplayMode: PageDisplayMode = PageDisplayMode.AUTOMATIC,
    val separateCover: Boolean = true,
    val panoramasAlone: Boolean = true,
    val libraryStyleId: String = LibraryStyle.default.id,
    val readerCanvasBackgroundId: String = ReaderCanvasBackground.default.id,
    val bitmap: Bitmap? = null,
    val previousPageBitmap: Bitmap? = null,
    val nextPageBitmap: Bitmap? = null,
    val previousPageRegions: List<GuidedRegion> = emptyList(),
    val nextPageRegions: List<GuidedRegion> = emptyList(),
    val spreadPageIndex: Int? = null,
    val spreadPageBitmap: Bitmap? = null,
    val spreadPageRegions: List<GuidedRegion> = emptyList(),
    val previousSpreadImages: ReaderSpreadImages? = null,
    val nextSpreadImages: ReaderSpreadImages? = null,
    val guidedRegions: List<GuidedRegion> = emptyList(),
    val activeRegionIndex: Int = -1,
    val guidedModeEnabled: Boolean = true,
    val isAnalyzing: Boolean = false,
    val analyzedPages: Int = 0,
    val analysisPageCount: Int = 0,
    val isAnalyzingCurrentPage: Boolean = false,
    val analysisError: String? = null,
    val selectionMode: Boolean = false,
    val selection: NormalizedRect? = null,
    val guidedBitmap: Bitmap? = null,
    val isPreparingZoom: Boolean = false,
    val isRecognizing: Boolean = false,
    val recognizedText: String? = null,
    val error: String? = null,
    val publicationFormatId: String? = null,
    val publicationAuthor: String? = null,
    val publicationId: String? = null,
    val publicationRootPath: String? = null,
    val publicationChapters: List<PublicationChapter> = emptyList(),
    val publicationCapabilities: PublicationCapabilities = PublicationCapabilities(),
    val bookProgress: Float = 0f,
    val bookFontSizeSp: Float = 20f,
    val bookColumnMode: BookColumnMode = BookColumnMode.AUTOMATIC,
    val bookLineSpacing: Float = 1.72f,
    val bookMarginsDp: Float = 26f,
    val publicationBookmarks: List<PublicationBookmark> = emptyList(),
    val pageBookmarks: List<PublicationBookmark> = emptyList(),
    val pdfTextSearchResults: List<PdfPageDocument.TextSearchResult> = emptyList(),
    val isSearchingPdfText: Boolean = false,
    val pdfTextSearchError: String? = null,
    val pageSearchMatches: List<com.estante.reader.core.guided.NormalizedRect> = emptyList(),
    val publicationHighlights: List<BookHighlightEntity> = emptyList(),
    val bookHighlightColorId: String = BookHighlightColor.default.id,
    val pendingBookHighlight: BookHighlightEntity? = null,
    val publicationSearchResults: List<PublicationSearchResult> = emptyList(),
    val isSearchingPublication: Boolean = false,
)

class EstanteViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    private val libraryRepository = ComicLibraryRepository(ComicDatabase.getInstance(application).comicDao())
    private val bookHighlightRepository = BookHighlightRepository(ComicDatabase.getInstance(application).bookHighlightDao())
    private val bookHighlightMutationMutex = Mutex()
    private val publicationImporters = PublicationImportRegistry()
    private val _libraryItems = MutableStateFlow<List<LibraryComicUi>>(emptyList())
    val libraryItems: StateFlow<List<LibraryComicUi>> = _libraryItems.asStateFlow()

    private var currentComicId: String? = null
    private var pageDocument: PageDocument? = null
    private var sourceUri: Uri? = null
    private var cachedArchiveUri: Uri? = null
    private var cachedArchiveFile: File? = null
    private var cachedArchiveFingerprint: String? = null
    private var documentFingerprint: String? = null
    private var pageJob: Job? = null
    private var adjacentPagesJob: Job? = null
    private var adjacentSpreadsJob: Job? = null
    private var spreadPageJob: Job? = null
    private var openJob: Job? = null
    private var bookProgressJob: Job? = null
    private var bookSettingsJob: Job? = null
    private var bookHighlightsJob: Job? = null
    private var pdfSearchJob: Job? = null
    private var pdfSearchRequestId = 0L
    private var analysisJob: Job? = null
    private var focusLastRegionOnPageLoad = false
    private var preferredRegionIndex = 0
    private val analysisStore = PageAnalysisStore(application)
    private var pageRequestId = 0L
    private var openRequestId = 0L
    private var recognitionRequestId = 0L

    init {
        viewModelScope.launch {
            val attemptedCovers = mutableSetOf<String>()
            libraryRepository.observeLibrary().collect { comics ->
                comics.filter { it.format == ComicFormat.EPUB && (it.coverPath == null || !File(application.filesDir, it.coverPath).isFile) }.forEach { book ->
                    if (attemptedCovers.add(book.id)) withContext(Dispatchers.IO) {
                        runCatching {
                            val root = File(application.filesDir, "library/publications/${book.id}")
                            val cover = com.estante.reader.core.publication.PublicationCoverResolver.find(root) ?: return@runCatching
                            val relative = "library/covers/${book.id}.jpg"
                            writeCoverThumbnail(cover.readBytes(), File(application.filesDir, relative))
                            PublicationManifestStore.load(root)?.copy(coverFile = cover)?.let(PublicationManifestStore::save)
                            libraryRepository.updateCover(book.id, 0, relative)
                        }
                    }
                }
                _libraryItems.value = comics.map { comic ->
                    LibraryComicUi(
                        id = comic.id,
                        title = comic.title,
                        formatId = comic.format.name,
                        coverPath = comic.coverPath?.let { File(getApplication<Application>().filesDir, it).absolutePath },
                        pageIndex = comic.lastPageIndex,
                        pageCount = comic.pageCount,
                        lastOpenedAt = comic.lastOpenedAt ?: comic.addedAt,
                    )
                }
            }
        }
        viewModelScope.launch {
            val requestId = openRequestId
            val preferences = getApplication<Application>().readerPreferences.data.first()
            val direction = preferences[ReadingDirectionPreference]
                ?.let { value -> runCatching { ReadingDirection.valueOf(value) }.getOrNull() }
                ?: ReadingDirection.LEFT_TO_RIGHT
            val displayMode = preferences[PageDisplayModePreference]
                ?.let { value -> runCatching { PageDisplayMode.valueOf(value) }.getOrNull() }
                ?: PageDisplayMode.AUTOMATIC
            val libraryStyle = LibraryStyle.fromId(preferences[LibraryStylePreference])
            val canvasBackground = ReaderCanvasBackground.fromId(preferences[ReaderCanvasBackgroundPreference])
            _state.value = _state.value.copy(
                readingDirection = direction,
                pageDisplayMode = displayMode,
                separateCover = preferences[SeparateCoverPreference] ?: true,
                panoramasAlone = preferences[PanoramasAlonePreference] ?: true,
                libraryStyleId = libraryStyle.id,
                readerCanvasBackgroundId = canvasBackground.id,
                guidedModeEnabled = preferences[GuidedModePreference] ?: true,
            )
            val savedUri = preferences[LastDocumentUri]?.let(Uri::parse) ?: return@launch
            val savedPage = preferences[LastPageIndex] ?: 0
            val title = withContext(Dispatchers.IO) { runCatching { displayName(savedUri) }.getOrNull() }
            withContext(Dispatchers.IO) {
                if (libraryRepository.findBySourceUri(savedUri) == null) {
                    libraryRepository.migrateLegacyLastRead(savedUri, savedPage, title ?: "Quadrinho")
                }
            }
            if (requestId == openRequestId && !_state.value.isLoading && _state.value.title == null) {
                _state.value = _state.value.copy(
                    lastReadTitle = title ?: "Última leitura",
                    lastReadPage = savedPage,
                )
            }
        }
    }

    fun openDocument(uri: Uri, persistPermission: Boolean = true, startPage: Int = 0) {
        if (persistPermission) {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        importComic(uri)
    }

    private fun importComic(uri: Uri) {
        val importId = UUID.randomUUID().toString()
        val app = getApplication<Application>()
        val displayName = runCatching { displayName(uri) }.getOrDefault("Quadrinho")
        val title = displayName.substringBeforeLast('.', displayName).ifBlank { "Quadrinho" }
        _state.value = _state.value.copy(isImporting = true, error = null)
        viewModelScope.launch {
            var rowCreated = false
            var recordId: String? = null
            var staging: File? = null
            var preparedDocument: OpenedPageDocument? = null
            try {
                val comicsDir = File(app.filesDir, "library/comics")
                val stagingDir = File(app.filesDir, "library/imports").apply {
                    if (!exists() && !mkdirs()) error("Não foi possível preparar a importação.")
                }
                val imported = withContext(Dispatchers.IO) {
                    val stage = File.createTempFile("comic-", ".part", stagingDir)
                    staging = stage
                    val digest = MessageDigest.getInstance("SHA-256")
                    val source = app.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("O Android não conseguiu abrir este arquivo.")
                    source.use { input ->
                        FileOutputStream(stage).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                if (copied > MAX_DOCUMENT_BYTES) {
                                    throw IllegalArgumentException("O arquivo excede o limite de 512 MiB.")
                                }
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    val existing = libraryRepository.findByContentHash(hash)
                    if (existing != null) return@withContext ImportResult(existing, null, null)
                    val textImporter = publicationImporters.importerFor(stage, displayName)
                    if (textImporter != null) {
                        return@withContext importTextPublication(
                            stage = stage,
                            importer = textImporter,
                            displayName = displayName,
                            title = title,
                            sourceUri = uri,
                            contentHash = hash,
                            importId = importId,
                            stagingDir = stagingDir,
                        )
                    }
                    val openedDocument = PageDocumentFactory.openFile(stage, File(app.cacheDir, "cbr-pages/$importId"))
                    preparedDocument = openedDocument
                    val document = openedDocument.document
                    val format = openedDocument.format
                    val pageCount = document.pageCount
                    val coverBytes = document.readPage(0, maxDimension = 1600, maxPixels = 2_560_000L)
                    if (!comicsDir.exists() && !comicsDir.mkdirs()) {
                        throw IllegalStateException("Não foi possível guardar o quadrinho.")
                    }
                    val extension = format.name.lowercase()
                    val target = File(comicsDir, hash + "." + extension)
                    val priorByUri = libraryRepository.findBySourceUri(uri)
                    val id = priorByUri?.takeIf { it.contentHash == null }?.id ?: hash
                    recordId = id
                    val managed = if (target.exists()) target else File(comicsDir, id + "." + extension)
                    val coverRelative = "library/covers/" + id + ".jpg"
                    val coverFile = File(app.filesDir, coverRelative)
                    if (priorByUri == null) {
                        libraryRepository.beginImport(id, title, displayName, format, uri, managed.absolutePath)
                    } else {
                        libraryRepository.upsert(
                            priorByUri.copy(
                                contentHash = null,
                                title = title,
                                displayName = displayName,
                                format = format,
                                managedPath = managed.absolutePath,
                                importState = ComicImportState.IMPORTING,
                                importError = null,
                                pageCount = 0,
                                coverPath = null,
                            ),
                        )
                    }
                    rowCreated = true
                    if (!target.exists() && !stage.renameTo(target)) {
                        throw IllegalStateException("Não foi possível guardar o quadrinho.")
                    }
                    if (target.exists()) stage.delete()
                    staging = null
                    if (managed != target && !target.renameTo(managed)) {
                        throw IllegalStateException("Não foi possível finalizar o arquivo importado.")
                    }
                    writeCoverThumbnail(coverBytes, coverFile)
                    coverBytes.fill(0)
                    libraryRepository.markReady(id, hash, managed.absolutePath, pageCount, coverPath = coverRelative)
                    val comic = libraryRepository.getById(id) ?: error("O registro da estante desapareceu.")
                    ImportResult(comic, openedDocument, null)
                }
                staging?.delete()
                openComic(imported.comic.id, imported.document, imported.publication)
            } catch (error: Exception) {
                preparedDocument?.document?.close()
                staging?.delete()
                if (rowCreated) recordId?.let { id -> runCatching { libraryRepository.markFailed(id, friendlyError(error)) } }
                _state.value = _state.value.copy(isImporting = false, isLoading = false, error = friendlyError(error))
            }
        }
    }

    private data class ImportResult(
        val comic: ComicEntity,
        val document: OpenedPageDocument?,
        val publication: ImportedPublication?,
    )

    private suspend fun importTextPublication(
        stage: File,
        importer: PublicationImporter,
        displayName: String,
        title: String,
        sourceUri: Uri,
        contentHash: String,
        importId: String,
        stagingDir: File,
    ): ImportResult {
        val app = getApplication<Application>()
        val publicationStage = File(stagingDir, "publication-$importId")
        var rowCreated = false
        var recordId: String? = null
        var installedRoot: File? = null
        try {
            val parsed = importer.import(stage, publicationStage, title)
            val format = ComicFormat.valueOf(parsed.formatId)
            val priorByUri = libraryRepository.findBySourceUri(sourceUri)
            val id = priorByUri?.takeIf { it.contentHash == null }?.id ?: contentHash
            recordId = id
            val comicsDir = File(app.filesDir, "library/comics").apply { if (!exists() && !mkdirs()) error("Não foi possível guardar o livro.") }
            val extension = format.name.lowercase()
            val contentTarget = File(comicsDir, "$contentHash.$extension")
            val managed = if (contentTarget.exists()) contentTarget else File(comicsDir, "$id.$extension")
            if (priorByUri == null) {
                libraryRepository.beginImport(id, parsed.title, displayName, format, sourceUri, managed.absolutePath)
            } else {
                libraryRepository.upsert(priorByUri.copy(
                    contentHash = null,
                    title = parsed.title,
                    displayName = displayName,
                    format = format,
                    managedPath = managed.absolutePath,
                    importState = ComicImportState.IMPORTING,
                    importError = null,
                    pageCount = 0,
                    coverPath = null,
                ))
            }
            rowCreated = true
            if (!contentTarget.exists() && !stage.renameTo(contentTarget)) error("Não foi possível guardar o livro importado.")
            stage.delete()
            val publishedFile = if (managed == contentTarget) contentTarget else {
                if (managed.exists()) managed.delete()
                if (!contentTarget.renameTo(managed)) error("Não foi possível finalizar o livro importado.")
                managed
            }
            val publicationTarget = File(app.filesDir, "library/publications/$id")
            publicationTarget.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("Não foi possível preparar o livro para leitura.") }
            publicationTarget.deleteRecursively()
            if (!publicationStage.renameTo(publicationTarget)) error("Não foi possível finalizar os capítulos do livro.")
            installedRoot = publicationTarget
            val installed = parsed.copy(
                root = publicationTarget,
                coverFile = parsed.coverFile?.let { File(publicationTarget, it.canonicalFile.relativeTo(publicationStage.canonicalFile).path) },
            )
            PublicationManifestStore.save(installed)
            val coverRelative = installed.coverFile?.let { cover ->
                val relative = "library/covers/$id.jpg"
                runCatching { writeCoverThumbnail(cover.readBytes(), File(app.filesDir, relative)) }.getOrNull()
                    ?.let { relative }
            }
            libraryRepository.markReady(id, contentHash, publishedFile.absolutePath, installed.chapters.size, coverPath = coverRelative)
            val comic = libraryRepository.getById(id) ?: error("O registro da estante desapareceu.")
            return ImportResult(comic, null, installed)
        } catch (error: Exception) {
            publicationStage.deleteRecursively()
            installedRoot?.deleteRecursively()
            if (rowCreated) recordId?.let { id -> runCatching { libraryRepository.markFailed(id, friendlyError(error)) } }
            throw error
        }
    }

    fun openComic(id: String) = openComic(id, null, null)

    private fun openComic(id: String, preparedDocument: OpenedPageDocument?, preparedPublication: ImportedPublication?) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            var openedDocument = preparedDocument
            try {
                val comic = libraryRepository.getById(id) ?: error("Este quadrinho não está na estante.")
                if (comic.managedPath == null) {
                    val legacyUri = comic.sourceUri?.let(Uri::parse) ?: error("O arquivo precisa ser selecionado novamente.")
                    preparedDocument?.document?.close()
                    importComic(legacyUri)
                    return@launch
                }
                val file = File(comic.managedPath)
                if (!file.isFile) error("O arquivo importado não foi encontrado; importe-o novamente.")
                if (comic.format == ComicFormat.EPUB || comic.format == ComicFormat.MOBI) {
                    pageDocument?.close()
                    pageDocument = null
                    currentComicId = comic.id
                    sourceUri = null
                    documentFingerprint = null
                    val root = File(getApplication<Application>().filesDir, "library/publications/${comic.id}")
                    val publication = preparedPublication ?: withContext(Dispatchers.IO) {
                        PublicationManifestStore.load(root) ?: run {
                            val importer = publicationImporters.importerFor(file, comic.displayName)
                                ?: error("O formato deste livro não é mais compatível.")
                            importer.import(file, root, comic.title).also(PublicationManifestStore::save)
                        }
                    }
                    val preferences = getApplication<Application>().readerPreferences.data.first()
                    val savedLocation = preferences[stringPreferencesKey("$BookLocationPrefix${comic.id}")]
                        ?.split('|')
                    val savedChapter = savedLocation?.getOrNull(0)?.toIntOrNull()
                        ?: comic.lastPageIndex
                    val chapterIndex = savedChapter.coerceIn(0, publication.chapters.lastIndex)
                    val savedProgress = savedLocation?.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
                    val fontSize = preferences[stringPreferencesKey("$BookFontSizePrefix${comic.id}")]
                        ?.toFloatOrNull()?.coerceIn(16f, 32f) ?: 20f
                    val columnMode = BookColumnMode.fromId(preferences[stringPreferencesKey("$BookColumnModePrefix${comic.id}")])
                    val lineSpacing = preferences[stringPreferencesKey("$BookLineSpacingPrefix${comic.id}")]
                        ?.toFloatOrNull()?.coerceIn(1.3f, 2.2f) ?: 1.72f
                    val margins = preferences[stringPreferencesKey("$BookMarginsPrefix${comic.id}")]
                        ?.toFloatOrNull()?.coerceIn(12f, 48f) ?: 26f
                    val bookmarks = runCatching {
                        val array = org.json.JSONArray(preferences[stringPreferencesKey("$BookmarksPrefix${comic.id}")].orEmpty())
                        (0 until array.length()).map { index ->
                            val item = array.getJSONObject(index)
                            PublicationBookmark(item.getInt("chapter"), item.getDouble("progress").toFloat(), item.getString("title"))
                        }
                    }.getOrDefault(emptyList())
                    val highlightColorId = BookHighlightColor.fromId(
                        preferences[stringPreferencesKey("$BookHighlightColorPrefix${comic.id}")],
                    ).id
                    val background = ReaderCanvasBackground.fromId(preferences[ReaderCanvasBackgroundPreference])
                    getApplication<Application>().readerPreferences.edit {
                        it[LastDocumentUri] = comic.sourceUri ?: Uri.fromFile(file).toString()
                        it[LastPageIndex] = chapterIndex
                    }
                    _state.value = ReaderUiState(
                        title = publication.title,
                        lastReadTitle = publication.title,
                        pageCount = publication.chapters.size,
                        pageIndex = chapterIndex,
                        lastReadPage = chapterIndex,
                        libraryStyleId = _state.value.libraryStyleId,
                        readerCanvasBackgroundId = background.id,
                        publicationFormatId = publication.formatId,
                        publicationAuthor = publication.author,
                        publicationId = comic.id,
                        publicationRootPath = publication.root.absolutePath,
                        publicationChapters = publication.chapters,
                        publicationCapabilities = publication.capabilities,
                        bookProgress = savedProgress,
                        bookFontSizeSp = fontSize,
                        bookColumnMode = columnMode,
                        bookLineSpacing = lineSpacing,
                        bookMarginsDp = margins,
                        publicationBookmarks = bookmarks,
                        bookHighlightColorId = highlightColorId,
                        isLoading = false,
                    )
                    bookHighlightsJob?.cancel()
                    bookHighlightsJob = viewModelScope.launch {
                        bookHighlightRepository.observeForBook(comic.id).collect { highlights ->
                            if (_state.value.publicationId == comic.id) {
                                _state.value = _state.value.copy(publicationHighlights = highlights)
                            }
                        }
                    }
                    libraryRepository.recordOpened(id, publication.title, publication.chapters.size, chapterIndex)
                    return@launch
                }
                if (openedDocument == null) {
                    openedDocument = withContext(Dispatchers.IO) {
                        PageDocumentFactory.openFile(file, File(getApplication<Application>().cacheDir, "cbr-pages/$id"))
                    }
                }
                val activeDocument = requireNotNull(openedDocument)
                val indexed = activeDocument.document
                val fingerprint = comic.contentHash ?: withContext(Dispatchers.IO) { sha256File(file) }
                pageDocument?.close()
                pageDocument = indexed
                currentComicId = comic.id
                sourceUri = Uri.fromFile(file)
                documentFingerprint = fingerprint
                val page = comic.lastPageIndex.coerceIn(0, indexed.pageCount - 1)
                val savedPreferences = getApplication<Application>().readerPreferences.data.first()
                val comicDisplayMode = savedPreferences[stringPreferencesKey("page_display_mode_${comic.id}")]
                    ?.let { value -> runCatching { PageDisplayMode.valueOf(value) }.getOrNull() }
                    ?: savedPreferences[PageDisplayModePreference]?.let { value ->
                        runCatching { PageDisplayMode.valueOf(value) }.getOrNull()
                    }
                    ?: PageDisplayMode.AUTOMATIC
                val comicSeparateCover = savedPreferences[booleanPreferencesKey("separate_cover_${comic.id}")]
                    ?: savedPreferences[SeparateCoverPreference]
                    ?: true
                val comicPanoramasAlone = savedPreferences[booleanPreferencesKey("panoramas_alone_${comic.id}")]
                    ?: savedPreferences[PanoramasAlonePreference]
                    ?: true
                val pageBookmarks = runCatching {
                    val array = org.json.JSONArray(savedPreferences[stringPreferencesKey("$BookmarksPrefix${comic.id}")].orEmpty())
                    (0 until array.length()).map { index ->
                        val item = array.getJSONObject(index)
                        PublicationBookmark(item.getInt("chapter"), item.optDouble("progress", 0.0).toFloat(), item.optString("title"))
                    }.filter { it.chapterIndex in 0 until indexed.pageCount }
                }.getOrDefault(emptyList())
                val guidedModeForDocument = if (comic.format == ComicFormat.PDF) {
                    savedPreferences[booleanPreferencesKey("$GuidedModeDocumentPrefix${comic.id}")] ?: false
                } else _state.value.guidedModeEnabled
                preferredRegionIndex = comic.lastRegionId?.let { regionId ->
                    val saved = analysisStore.read(fingerprint, OrtBubbleDetector.VERSION, page).orEmpty()
                    GuidedPage(saved).orderedRegions(_state.value.readingDirection).indexOfFirst { it.id == regionId }
                }?.takeIf { it >= 0 } ?: 0
                getApplication<Application>().readerPreferences.edit {
                    it[LastDocumentUri] = comic.sourceUri ?: Uri.fromFile(file).toString()
                    it[LastPageIndex] = page
                }
                val cachedPages = withContext(Dispatchers.IO) {
                    (0 until indexed.pageCount).count { pageIndex ->
                        analysisStore.read(fingerprint, OrtBubbleDetector.VERSION, pageIndex) != null
                    }
                }
                _state.value = ReaderUiState(
                    title = comic.title,
                    documentFormatId = comic.format.name,
                    pageBookmarks = pageBookmarks,
                    lastReadTitle = comic.title,
                    pageCount = indexed.pageCount,
                    pageIndex = page,
                    lastReadPage = page,
                    libraryStyleId = _state.value.libraryStyleId,
                    readerCanvasBackgroundId = _state.value.readerCanvasBackgroundId,
                    guidedModeEnabled = guidedModeForDocument,
                    readingDirection = _state.value.readingDirection,
                    pageDisplayMode = comicDisplayMode,
                    separateCover = comicSeparateCover,
                    panoramasAlone = comicPanoramasAlone,
                    isAnalyzing = guidedModeForDocument && cachedPages < indexed.pageCount,
                    analyzedPages = cachedPages,
                    analysisPageCount = indexed.pageCount,
                    isLoading = true,
                )
                libraryRepository.recordOpened(id, comic.title, indexed.pageCount, page, comic.lastRegionId)
                loadPage(page)
                if (guidedModeForDocument) {
                    startAnalysis(indexed, Uri.fromFile(file), fingerprint)
                }
            } catch (error: Exception) {
                if (openedDocument?.document !== pageDocument) openedDocument?.document?.close()
                closePageDocument()
                currentComicId = null
                sourceUri = null
                _state.value = _state.value.copy(isLoading = false, isImporting = false, error = friendlyError(error))
            } finally {
                openJob = null
            }
        }
    }

    fun removeComic(id: String) {
        viewModelScope.launch {
            val comic = libraryRepository.getById(id) ?: return@launch
            if (currentComicId == id) {
                bookProgressJob?.cancel()
                bookSettingsJob?.cancel()
                closeDocument()
            }
            libraryRepository.remove(id)
            comic.managedPath?.let { File(it).delete() }
            comic.coverPath?.let { File(getApplication<Application>().filesDir, it).delete() }
            File(getApplication<Application>().filesDir, "library/publications/$id").deleteRecursively()
            getApplication<Application>().readerPreferences.edit { preferences ->
                preferences.remove(stringPreferencesKey("$BookLocationPrefix$id"))
                preferences.remove(stringPreferencesKey("$BookFontSizePrefix$id"))
                preferences.remove(stringPreferencesKey("$BookColumnModePrefix$id"))
                preferences.remove(stringPreferencesKey("$BookLineSpacingPrefix$id"))
                preferences.remove(stringPreferencesKey("$BookMarginsPrefix$id"))
                preferences.remove(stringPreferencesKey("$BookmarksPrefix$id"))
                preferences.remove(stringPreferencesKey("$BookHighlightColorPrefix$id"))
                preferences.remove(stringPreferencesKey("page_display_mode_$id"))
                preferences.remove(booleanPreferencesKey("separate_cover_$id"))
                preferences.remove(booleanPreferencesKey("$GuidedModeDocumentPrefix$id"))
            }
        }
    }

    private fun writeCoverThumbnail(bytes: ByteArray, target: File) {
        val parent = target.parentFile ?: error("Caminho de capa inválido.")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Não foi possível preparar a capa.")
        val full = decodePage(bytes, maxDimension = 1600, maxPixels = 2_560_000L)
        val sample = maxOf(1, maxOf(full.width / 600, full.height / 900))
        val thumb = if (sample > 1) Bitmap.createScaledBitmap(full, full.width / sample, full.height / sample, true) else full
        try {
            FileOutputStream(target).use { output ->
                if (!thumb.compress(Bitmap.CompressFormat.JPEG, 86, output)) {
                    throw IllegalStateException("Não foi possível gerar a capa.")
                }
                output.fd.sync()
            }
        } finally {
            if (thumb !== full) thumb.recycle()
            full.recycle()
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun closeDocument() {
        openRequestId++
        pageRequestId++
        recognitionRequestId++
        openJob?.cancel()
        pageJob?.cancel()
        adjacentPagesJob?.cancel()
        adjacentSpreadsJob?.cancel()
        spreadPageJob?.cancel()
        analysisJob?.cancel()
        pdfSearchJob?.cancel()
        pdfSearchRequestId++
        bookHighlightsJob?.cancel()
        closePageDocument()
        sourceUri = null
        documentFingerprint = null
        val closingComicId = currentComicId
        currentComicId = null
        val current = _state.value
        if (closingComicId != null && current.title != null) {
            viewModelScope.launch {
                libraryRepository.updateProgress(
                    closingComicId,
                    current.pageIndex.coerceAtLeast(0),
                    current.guidedRegions.getOrNull(current.activeRegionIndex)?.id,
                )
            }
        }
        _state.value = ReaderUiState(
            lastReadTitle = current.title ?: current.lastReadTitle,
            lastReadPage = current.pageIndex.takeIf { current.title != null } ?: current.lastReadPage,
            libraryStyleId = current.libraryStyleId,
            readerCanvasBackgroundId = current.readerCanvasBackgroundId,
            guidedModeEnabled = current.guidedModeEnabled,
        )
    }

    fun showLibrary() {
        closeDocument()
    }

    fun resumeLastDocument() {
        viewModelScope.launch {
            val comic = libraryRepository.observeLibrary().first()
                .firstOrNull { it.importState == ComicImportState.READY }
            if (comic != null) {
                openComic(comic.id)
                return@launch
            }
            val preferences = getApplication<Application>().readerPreferences.data.first()
            val savedUri = preferences[LastDocumentUri]?.let(Uri::parse) ?: return@launch
            openDocument(savedUri, persistPermission = false, startPage = preferences[LastPageIndex] ?: 0)
        }
    }

    fun goToPage(index: Int) {
        val current = _state.value
        if (index in 0 until current.pageCount && index != current.pageIndex) {
            _state.value = current.copy(pageSearchMatches = emptyList())
            if (!focusLastRegionOnPageLoad) preferredRegionIndex = 0
            loadPage(index)
        }
    }

    fun searchPdfText(query: String) {
        val document = pageDocument as? PdfPageDocument ?: return
        val term = query.trim()
        if (term.isEmpty()) {
            pdfSearchRequestId++
            pdfSearchJob?.cancel()
            _state.value = _state.value.copy(pdfTextSearchResults = emptyList(), isSearchingPdfText = false, pdfTextSearchError = null)
            return
        }
        val requestId = ++pdfSearchRequestId
        pdfSearchJob?.cancel()
        val uri = sourceUri
        _state.value = _state.value.copy(isSearchingPdfText = true, pdfTextSearchResults = emptyList(), pdfTextSearchError = null)
        pdfSearchJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { document.searchText(term) }
                if (requestId == pdfSearchRequestId && sourceUri == uri) {
                    _state.value = _state.value.copy(
                        pdfTextSearchResults = results.orEmpty(),
                        isSearchingPdfText = false,
                        pdfTextSearchError = if (results == null) "A busca textual em PDF requer Android 15 ou posterior. PDFs digitalizados também precisam de OCR." else null,
                    )
                }
            } catch (error: Exception) {
                if (requestId == pdfSearchRequestId && sourceUri == uri && error !is kotlinx.coroutines.CancellationException) {
                    _state.value = _state.value.copy(isSearchingPdfText = false, pdfTextSearchError = "Não foi possível buscar o texto deste PDF.")
                }
            }
        }
    }

    fun openPdfTextSearchResult(result: PdfPageDocument.TextSearchResult) {
        goToPage(result.pageIndex)
        _state.value = _state.value.copy(
            pageSearchMatches = result.matches.map { bounds ->
                NormalizedRect.clipped(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }.filterNotNull(),
        )
    }

    fun recordBookProgress(chapterIndex: Int, progression: Float) {
        val current = _state.value
        if (current.publicationRootPath == null || chapterIndex !in current.publicationChapters.indices) return
        val safeProgress = progression.coerceIn(0f, 1f)
        if (current.pageIndex != chapterIndex || kotlin.math.abs(current.bookProgress - safeProgress) > 0.01f) {
            _state.value = current.copy(pageIndex = chapterIndex, lastReadPage = chapterIndex, bookProgress = safeProgress)
        }
        val id = currentComicId ?: return
        bookProgressJob?.cancel()
        bookProgressJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            getApplication<Application>().readerPreferences.edit { preferences ->
                preferences[stringPreferencesKey("$BookLocationPrefix$id")] = "$chapterIndex|$safeProgress"
                preferences[LastPageIndex] = chapterIndex
            }
            libraryRepository.updateProgress(id, chapterIndex)
        }
    }

    fun goToBookChapter(index: Int) {
        val current = _state.value
        if (index !in current.publicationChapters.indices) return
        recordBookProgress(index, 0f)
        currentComicId?.let { id ->
            viewModelScope.launch {
                libraryRepository.recordOpened(id, current.title ?: "Livro", current.pageCount, index)
            }
        }
    }

    fun setBookFontSize(sizeSp: Float) {
        val size = sizeSp.coerceIn(16f, 32f)
        _state.value = _state.value.copy(bookFontSizeSp = size)
        persistBookSettings()
    }

    fun setBookLineSpacing(value: Float) {
        val spacing = value.coerceIn(1.3f, 2.2f)
        _state.value = _state.value.copy(bookLineSpacing = spacing)
        persistBookSettings()
    }

    fun setBookMargins(valueDp: Float) {
        val margins = valueDp.coerceIn(12f, 48f)
        _state.value = _state.value.copy(bookMarginsDp = margins)
        persistBookSettings()
    }

    fun setBookColumnMode(mode: BookColumnMode) {
        _state.value = _state.value.copy(bookColumnMode = mode)
        persistBookSettings()
    }

    private fun persistBookSettings() {
        val id = currentComicId ?: return
        val settings = _state.value
        bookSettingsJob?.cancel()
        bookSettingsJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            getApplication<Application>().readerPreferences.edit { preferences ->
                preferences[stringPreferencesKey("$BookFontSizePrefix$id")] = settings.bookFontSizeSp.toString()
                preferences[stringPreferencesKey("$BookLineSpacingPrefix$id")] = settings.bookLineSpacing.toString()
                preferences[stringPreferencesKey("$BookMarginsPrefix$id")] = settings.bookMarginsDp.toString()
                preferences[stringPreferencesKey("$BookColumnModePrefix$id")] = settings.bookColumnMode.id
            }
        }
    }

    fun addBookBookmark() {
        val current = _state.value
        val chapter = current.publicationChapters.getOrNull(current.pageIndex) ?: return
        val title = "${chapter.title} · ${(current.bookProgress * 100).toInt()}%"
        val bookmark = PublicationBookmark(current.pageIndex, current.bookProgress, title)
        val updated = (current.publicationBookmarks + bookmark).distinctBy { it.chapterIndex to (it.progression * 100).toInt() }
        _state.value = current.copy(publicationBookmarks = updated)
        currentComicId?.let { id -> viewModelScope.launch {
            val array = org.json.JSONArray()
            updated.forEach { item -> array.put(org.json.JSONObject().put("chapter", item.chapterIndex).put("progress", item.progression).put("title", item.title)) }
            getApplication<Application>().readerPreferences.edit { it[stringPreferencesKey("$BookmarksPrefix$id")] = array.toString() }
        } }
    }

    fun addPageBookmark() {
        val current = _state.value
        if (current.publicationRootPath != null || current.pageCount == 0) return
        val bookmark = PublicationBookmark(current.pageIndex, 0f, "Página ${current.pageIndex + 1}")
        val updated = (current.pageBookmarks + bookmark).distinctBy { it.chapterIndex }
        _state.value = current.copy(pageBookmarks = updated)
        persistPageBookmarks(currentComicId, updated)
    }

    fun removePageBookmark(bookmark: PublicationBookmark) {
        val current = _state.value
        val updated = current.pageBookmarks.filterNot { it.chapterIndex == bookmark.chapterIndex }
        _state.value = current.copy(pageBookmarks = updated)
        persistPageBookmarks(currentComicId, updated)
    }

    private fun persistPageBookmarks(id: String?, bookmarks: List<PublicationBookmark>) {
        if (id == null) return
        viewModelScope.launch {
            val array = org.json.JSONArray()
            bookmarks.forEach { item ->
                array.put(org.json.JSONObject().put("chapter", item.chapterIndex).put("progress", item.progression).put("title", item.title))
            }
            getApplication<Application>().readerPreferences.edit { it[stringPreferencesKey("$BookmarksPrefix$id")] = array.toString() }
        }
    }

    /** Saves the browser's final text selection without interrupting the selection gesture. */
    fun addBookHighlight(
        chapterHref: String,
        selectedText: String,
        startOffset: Int,
        endOffset: Int,
        prefix: String,
        suffix: String,
    ) {
        val current = _state.value
        val bookId = current.publicationId ?: return
        val chapterIndex = current.publicationChapters.indexOfFirst { it.href == chapterHref }
        val quote = selectedText.trim().take(MAX_HIGHLIGHT_TEXT_LENGTH)
        if (chapterIndex < 0 || quote.length < MIN_HIGHLIGHT_TEXT_LENGTH ||
            startOffset < 0 || endOffset <= startOffset || endOffset - startOffset > MAX_HIGHLIGHT_TEXT_LENGTH + 64
        ) return
        if (current.publicationHighlights.any {
                it.chapterHref == chapterHref && it.startOffset == startOffset && it.endOffset == endOffset
            }
        ) return

        val highlight = BookHighlightEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterHref = chapterHref,
            startOffset = startOffset,
            endOffset = endOffset,
            selectedText = quote,
            prefix = prefix.takeLast(HIGHLIGHT_CONTEXT_LENGTH),
            suffix = suffix.take(HIGHLIGHT_CONTEXT_LENGTH),
            colorId = BookHighlightColor.fromId(current.bookHighlightColorId).id,
            createdAt = System.currentTimeMillis(),
        )
        _state.value = current.copy(
            publicationHighlights = (current.publicationHighlights + highlight)
                .sortedWith(compareBy<BookHighlightEntity> { it.chapterIndex }.thenBy { it.startOffset }),
        )
        viewModelScope.launch {
            runCatching {
                bookHighlightMutationMutex.withLock {
                    withContext(Dispatchers.IO) { bookHighlightRepository.insert(highlight) }
                }
            }
                .onFailure {
                    if (_state.value.publicationId == bookId) {
                        _state.value = _state.value.copy(
                            publicationHighlights = _state.value.publicationHighlights.filterNot { it.id == highlight.id },
                            error = "Não foi possível salvar este grifo.",
                        )
                    }
                }
        }
    }

    fun setBookHighlightColor(colorId: String) {
        val color = BookHighlightColor.fromId(colorId)
        val current = _state.value
        val bookId = current.publicationId ?: return
        if (current.bookHighlightColorId == color.id) return
        _state.value = current.copy(bookHighlightColorId = color.id)
        viewModelScope.launch {
            getApplication<Application>().readerPreferences.edit {
                it[stringPreferencesKey("$BookHighlightColorPrefix$bookId")] = color.id
            }
        }
    }

    fun changeBookHighlightColor(highlightId: String, colorId: String) {
        val color = BookHighlightColor.fromId(colorId)
        val current = _state.value
        val bookId = current.publicationId ?: return
        val previous = current.publicationHighlights.firstOrNull { it.id == highlightId } ?: return
        _state.value = current.copy(publicationHighlights = current.publicationHighlights.map {
            if (it.id == highlightId) it.copy(colorId = color.id) else it
        })
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                bookHighlightMutationMutex.withLock { bookHighlightRepository.updateColor(highlightId, bookId, color.id) }
            }
            if (updated == 0 && _state.value.publicationId == bookId) {
                _state.value = _state.value.copy(publicationHighlights = _state.value.publicationHighlights.map {
                    if (it.id == highlightId) previous else it
                })
            }
        }
    }

    fun removeBookHighlight(highlightId: String) {
        val current = _state.value
        val bookId = current.publicationId ?: return
        if (current.publicationHighlights.none { it.id == highlightId }) return
        _state.value = current.copy(publicationHighlights = current.publicationHighlights.filterNot { it.id == highlightId })
        viewModelScope.launch {
            bookHighlightMutationMutex.withLock {
                withContext(Dispatchers.IO) { bookHighlightRepository.delete(highlightId, bookId) }
            }
        }
    }

    fun restoreBookHighlight(highlight: BookHighlightEntity) {
        val current = _state.value
        if (current.publicationId != highlight.bookId || current.publicationHighlights.any { it.id == highlight.id }) return
        _state.value = current.copy(
            publicationHighlights = (current.publicationHighlights + highlight)
                .sortedWith(compareBy<BookHighlightEntity> { it.chapterIndex }.thenBy { it.startOffset }),
        )
        viewModelScope.launch {
            runCatching {
                bookHighlightMutationMutex.withLock {
                    withContext(Dispatchers.IO) { bookHighlightRepository.insert(highlight) }
                }
            }
        }
    }

    fun openBookHighlight(highlightId: String) {
        val highlight = _state.value.publicationHighlights.firstOrNull { it.id == highlightId } ?: return
        recordBookProgress(highlight.chapterIndex, 0f)
        _state.value = _state.value.copy(pendingBookHighlight = highlight)
    }

    fun clearPendingBookHighlight(highlightId: String) {
        if (_state.value.pendingBookHighlight?.id == highlightId) {
            _state.value = _state.value.copy(pendingBookHighlight = null)
        }
    }

    fun searchPublication(query: String) {
        val current = _state.value
        val rootPath = current.publicationRootPath ?: return
        val term = query.trim()
        if (term.isEmpty()) {
            _state.value = current.copy(publicationSearchResults = emptyList(), isSearchingPublication = false)
            return
        }
        val publicationId = current.publicationId
        _state.value = current.copy(isSearchingPublication = true, publicationSearchResults = emptyList())
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                val root = File(rootPath).canonicalFile
                buildList {
                    current.publicationChapters.forEachIndexed { chapterIndex, chapter ->
                        if (size >= 40) return@forEachIndexed
                        val source = File(root, chapter.href).canonicalFile
                        if (!source.path.startsWith(root.path + File.separator) || !source.isFile) return@forEachIndexed
                        val text = runCatching { org.jsoup.Jsoup.parse(source, "UTF-8").text() }.getOrNull().orEmpty()
                        if (text.isEmpty()) return@forEachIndexed
                        val regex = Regex(Regex.escape(term), RegexOption.IGNORE_CASE)
                        var cursor = 0
                        while (size < 40) {
                            val match = regex.find(text, cursor) ?: break
                            val start = (match.range.first - 72).coerceAtLeast(0)
                            val end = (match.range.last + 73).coerceAtMost(text.length)
                            add(PublicationSearchResult(
                                chapterIndex = chapterIndex,
                                progression = (match.range.first.toFloat() / text.length).coerceIn(0f, 1f),
                                title = chapter.title,
                                snippet = text.substring(start, end).replace(Regex("\\s+"), " "),
                            ))
                            cursor = match.range.last + 1
                        }
                        if (size >= 40) return@forEachIndexed
                    }
                }
            }
            if (_state.value.publicationId == publicationId) {
                _state.value = _state.value.copy(publicationSearchResults = results, isSearchingPublication = false)
            }
        }
    }

    fun setPageDisplayMode(mode: PageDisplayMode) {
        val current = _state.value
        _state.value = current.copy(pageDisplayMode = mode)
        viewModelScope.launch {
            getApplication<Application>().readerPreferences.edit {
                it[PageDisplayModePreference] = mode.name
                currentComicId?.let { id -> it[stringPreferencesKey("page_display_mode_$id")] = mode.name }
            }
        }
    }

    fun setLibraryStyle(styleId: String) {
        val style = LibraryStyle.fromId(styleId)
        _state.value = _state.value.copy(libraryStyleId = style.id)
        viewModelScope.launch {
            getApplication<Application>().readerPreferences.edit { preferences ->
                preferences[LibraryStylePreference] = style.id
            }
        }
    }

    fun setReaderCanvasBackground(background: ReaderCanvasBackground) {
        _state.value = _state.value.copy(readerCanvasBackgroundId = background.id)
        viewModelScope.launch {
            getApplication<Application>().readerPreferences.edit { preferences ->
                preferences[ReaderCanvasBackgroundPreference] = background.id
            }
        }
    }

    fun setSeparateCover(separate: Boolean) {
        val current = _state.value
        _state.value = current.copy(separateCover = separate)
        viewModelScope.launch {
            getApplication<Application>().readerPreferences.edit {
                it[SeparateCoverPreference] = separate
                currentComicId?.let { id -> it[booleanPreferencesKey("separate_cover_$id")] = separate }
            }
        }
    }

    fun setPanoramasAlone(enabled: Boolean) {
        val current = _state.value
        _state.value = current.copy(panoramasAlone = enabled)
        viewModelScope.launch {
            getApplication<Application>().readerPreferences.edit {
                it[PanoramasAlonePreference] = enabled
                currentComicId?.let { id -> it[booleanPreferencesKey("panoramas_alone_$id")] = enabled }
            }
        }
    }

    fun loadSpreadPage(index: Int?) {
        val current = _state.value
        if (index == null || index !in 0 until current.pageCount || index == current.pageIndex) {
            spreadPageJob?.cancel()
            _state.value = current.copy(spreadPageIndex = null, spreadPageBitmap = null, spreadPageRegions = emptyList())
            return
        }
        if (current.spreadPageIndex == index && current.spreadPageBitmap != null) return
        val currentDocument = pageDocument ?: return
        val currentUri = sourceUri ?: return
        spreadPageJob?.cancel()
        val requestId = pageRequestId
        _state.value = current.copy(spreadPageIndex = index, spreadPageBitmap = null, spreadPageRegions = emptyList())
        spreadPageJob = viewModelScope.launch(Dispatchers.IO) {
            var loaded: Bitmap? = null
            try {
                val bytes = currentDocument.readPage(index, maxDimension = 2400, maxPixels = 5_760_000L)
                loaded = withContext(Dispatchers.Default) {
                    decodePage(bytes, maxDimension = 2400, maxPixels = 5_760_000L)
                }
                val fingerprint = documentFingerprint
                val pageRegions = if (fingerprint == null) emptyList() else {
                    analysisStore.read(fingerprint, OrtBubbleDetector.VERSION, index)
                        ?.let { GuidedPage(it).orderedRegions(_state.value.readingDirection) }.orEmpty()
                }
                if (requestId != pageRequestId || sourceUri != currentUri || _state.value.spreadPageIndex != index) return@launch
                _state.value = _state.value.copy(spreadPageBitmap = loaded, spreadPageRegions = pageRegions)
                loaded = null
            } catch (error: Exception) {
                if (error !is kotlinx.coroutines.CancellationException && requestId == pageRequestId && sourceUri == currentUri) {
                    _state.value = _state.value.copy(
                        spreadPageBitmap = null,
                        error = "Não foi possível preparar a página dupla. A leitura continua em página única.",
                    )
                }
            } finally {
                loaded?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }

    fun loadNeighborSpreads(previousFirstPageIndex: Int?, nextFirstPageIndex: Int?) {
        val current = _state.value
        val currentDocument = pageDocument ?: return
        val currentUri = sourceUri ?: return
        val previousSpread = previousFirstPageIndex?.let {
            ReaderSpreadBuilder.forPage(it, current.pageCount, current.separateCover)
        }
        val nextSpread = nextFirstPageIndex?.let {
            ReaderSpreadBuilder.forPage(it, current.pageCount, current.separateCover)
        }
        adjacentSpreadsJob?.cancel()
        pdfSearchJob?.cancel()
        val requestId = pageRequestId
        val pageIndex = current.pageIndex
        _state.value = current.copy(previousSpreadImages = null, nextSpreadImages = null)
        adjacentSpreadsJob = viewModelScope.launch(Dispatchers.IO) {
            var previousImages: ReaderSpreadImages? = null
            var nextImages: ReaderSpreadImages? = null

            suspend fun readSpread(spread: ReaderSpread): ReaderSpreadImages {
                suspend fun readPage(index: Int): Bitmap {
                    val bytes = currentDocument.readPage(index, maxDimension = 1600, maxPixels = 2_560_000L)
                    return withContext(Dispatchers.Default) {
                        decodePage(bytes, maxDimension = 1600, maxPixels = 2_560_000L)
                    }
                }
                val first = readPage(spread.first)
                val second = try {
                    if (spread.second != null) readPage(spread.second) else null
                } catch (error: Exception) {
                    first.recycle()
                    throw error
                }
                return ReaderSpreadImages(spread, first, second)
            }

            try {
                previousImages = if (previousSpread != null) readSpread(previousSpread) else null
                nextImages = if (nextSpread != null) readSpread(nextSpread) else null
                if (requestId != pageRequestId || sourceUri != currentUri || _state.value.pageIndex != pageIndex) {
                    return@launch
                }
                _state.value = _state.value.copy(
                    previousSpreadImages = previousImages,
                    nextSpreadImages = nextImages,
                )
                previousImages = null
                nextImages = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
            } finally {
                previousImages?.let { images ->
                    images.firstBitmap.recycle()
                    images.secondBitmap?.recycle()
                }
                nextImages?.let { images ->
                    images.firstBitmap.recycle()
                    images.secondBitmap?.recycle()
                }
            }
        }
    }

    fun setGuidedModeEnabled(enabled: Boolean) {
        val current = _state.value
        val isPdf = current.documentFormatId == ComicFormat.PDF.name
        _state.value = current.copy(
            guidedModeEnabled = enabled,
            isAnalyzing = enabled && isPdf,
            analysisError = if (enabled) null else current.analysisError,
        )
        if (isPdf) {
            currentComicId?.let { id ->
                viewModelScope.launch {
                    getApplication<Application>().readerPreferences.edit {
                        it[booleanPreferencesKey("$GuidedModeDocumentPrefix$id")] = enabled
                    }
                }
            }
            if (enabled) {
                val document = pageDocument
                val uri = sourceUri
                val fingerprint = documentFingerprint
                if (document != null && uri != null && fingerprint != null) startAnalysis(document, uri, fingerprint)
            } else {
                analysisJob?.cancel()
                analysisJob = null
                _state.value = _state.value.copy(isAnalyzing = false, isAnalyzingCurrentPage = false)
            }
        } else {
            viewModelScope.launch {
                getApplication<Application>().readerPreferences.edit { it[GuidedModePreference] = enabled }
            }
        }
    }

    fun setReadingDirection(direction: ReadingDirection) {
        val current = _state.value
        val currentRegionId = current.guidedRegions.getOrNull(current.activeRegionIndex)?.id
        val reordered = GuidedPage(current.guidedRegions).orderedRegions(direction)
        _state.value = current.copy(
            readingDirection = direction,
            guidedRegions = reordered,
            previousPageRegions = GuidedPage(current.previousPageRegions).orderedRegions(direction),
            nextPageRegions = GuidedPage(current.nextPageRegions).orderedRegions(direction),
            spreadPageRegions = GuidedPage(current.spreadPageRegions).orderedRegions(direction),
            activeRegionIndex = currentRegionId?.let { id -> reordered.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 }
                ?: if (current.guidedModeEnabled && reordered.isNotEmpty()) 0 else -1,
        )
        preferredRegionIndex = _state.value.activeRegionIndex.coerceAtLeast(0)
        viewModelScope.launch {
            getApplication<Application>().readerPreferences.edit {
                it[ReadingDirectionPreference] = direction.name
                it[LastRegionIndex] = preferredRegionIndex
            }
        }
        pageDocument?.let { currentDocument ->
            sourceUri?.let { uri -> loadAdjacentPages(current.pageIndex, currentDocument, uri, pageRequestId) }
        }
    }

    fun focusRegion(index: Int) {
        val current = _state.value
        if (index in current.guidedRegions.indices) {
            _state.value = current.copy(activeRegionIndex = index, selectionMode = false)
            preferredRegionIndex = index
            val comicId = currentComicId
            val regionId = current.guidedRegions[index].id
            viewModelScope.launch {
                getApplication<Application>().readerPreferences.edit { it[LastRegionIndex] = index }
                if (comicId != null) libraryRepository.updateProgress(comicId, current.pageIndex, regionId)
            }
        }
    }

    fun nextRegion() {
        val current = _state.value
        if (current.guidedRegions.isEmpty()) {
            if (current.pageIndex + 1 < current.pageCount) {
                focusLastRegionOnPageLoad = false
                goToPage(current.pageIndex + 1)
            }
            return
        }
        if (current.activeRegionIndex + 1 < current.guidedRegions.size) {
            focusRegion(current.activeRegionIndex + 1)
        } else if (current.pageIndex + 1 < current.pageCount) {
            focusLastRegionOnPageLoad = false
            preferredRegionIndex = 0
            goToPage(current.pageIndex + 1)
        }
    }

    fun previousRegion() {
        val current = _state.value
        if (current.activeRegionIndex > 0) {
            focusRegion(current.activeRegionIndex - 1)
        } else if (current.pageIndex > 0) {
            focusLastRegionOnPageLoad = true
            goToPage(current.pageIndex - 1)
        }
    }

    private fun startAnalysis(currentDocument: PageDocument, uri: Uri, fingerprint: String) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val detector = try {
                withContext(Dispatchers.Default) { OrtBubbleDetector(getApplication()) }
            } catch (error: Exception) {
                if (sourceUri == uri) {
                    _state.value = _state.value.copy(
                        isAnalyzing = false,
                        analysisError = "Não foi possível preparar a detecção automática.",
                    )
                }
                return@launch
            }
            try {
                val firstPage = _state.value.pageIndex.coerceIn(0, currentDocument.pageCount - 1)
                val pageOrder = buildList {
                    add(firstPage)
                    if (firstPage + 1 < currentDocument.pageCount) add(firstPage + 1)
                    if (firstPage + 2 < currentDocument.pageCount) add(firstPage + 2)
                    addAll((0 until currentDocument.pageCount).filterNot { it in this })
                }
                for (pageIndex in pageOrder) {
                    currentCoroutineContext().ensureActive()
                    if (sourceUri != uri || documentFingerprint != fingerprint) return@launch
                    val cached = withContext(Dispatchers.IO) {
                        analysisStore.read(fingerprint, detector.modelVersion, pageIndex)
                    }
                    val detected = cached ?: run {
                        if (_state.value.pageIndex == pageIndex) {
                            _state.value = _state.value.copy(isAnalyzingCurrentPage = true, analysisError = null)
                        }
                        val bytes = withContext(Dispatchers.IO) {
                            currentDocument.readPage(pageIndex, maxDimension = 1600, maxPixels = 2_560_000L)
                        }
                        val analysisBitmap = withContext(Dispatchers.Default) { decodeAnalysisPage(bytes) }
                        try {
                            val pageDetections = withContext(Dispatchers.Default) { detector.detect(analysisBitmap) }
                            withContext(Dispatchers.IO) {
                                analysisStore.write(fingerprint, detector.modelVersion, pageIndex, pageDetections)
                            }
                            pageDetections
                        } finally {
                            analysisBitmap.recycle()
                        }
                    }
                    val regions = GuidedPage(detected).orderedRegions(_state.value.readingDirection)
                    val oldState = _state.value
                    val count = if (cached == null) oldState.analyzedPages + 1 else oldState.analyzedPages
                    _state.value = oldState.copy(
                        analyzedPages = count,
                        isAnalyzing = count < currentDocument.pageCount,
                        isAnalyzingCurrentPage = false,
                        guidedRegions = if (oldState.pageIndex == pageIndex) regions else oldState.guidedRegions,
                        previousPageRegions = if (oldState.pageIndex == pageIndex + 1) regions else oldState.previousPageRegions,
                        nextPageRegions = if (oldState.pageIndex == pageIndex - 1) regions else oldState.nextPageRegions,
                        spreadPageRegions = if (oldState.spreadPageIndex == pageIndex) regions else oldState.spreadPageRegions,
                        activeRegionIndex = if (oldState.pageIndex == pageIndex && regions.isNotEmpty()) {
                            if (focusLastRegionOnPageLoad) regions.lastIndex else preferredRegionIndex.coerceIn(0, regions.lastIndex)
                        } else oldState.activeRegionIndex,
                        analysisError = null,
                    )
                    if (oldState.pageIndex == pageIndex) focusLastRegionOnPageLoad = false
                }
                _state.value = _state.value.copy(isAnalyzing = false, isAnalyzingCurrentPage = false)
            } catch (error: Exception) {
                if (sourceUri == uri && error !is kotlinx.coroutines.CancellationException) {
                    _state.value = _state.value.copy(
                        isAnalyzing = false,
                        isAnalyzingCurrentPage = false,
                        analysisError = "A análise foi interrompida. Tente novamente ao abrir o quadrinho.",
                    )
                }
            } finally {
                detector.close()
                if (sourceUri == uri) analysisJob = null
            }
        }
    }

    fun setSelectionMode(enabled: Boolean) {
        recognitionRequestId++
        _state.value = _state.value.copy(
            selectionMode = enabled,
            selection = if (enabled) null else _state.value.selection,
            guidedBitmap = null,
            isPreparingZoom = false,
            recognizedText = null,
        )
    }

    fun setSelection(rect: NormalizedRect) {
        recognitionRequestId++
        _state.value = _state.value.copy(selection = rect, guidedBitmap = null, recognizedText = null)
    }

    fun createGuidedZoom() {
        val current = _state.value
        val bitmap = current.bitmap ?: return
        val rect = current.selection ?: return
        val requestId = pageRequestId
        viewModelScope.launch {
            _state.value = _state.value.copy(isPreparingZoom = true, error = null)
            try {
                val crop = withContext(Dispatchers.Default) { crop(bitmap, rect) }
                if (requestId == pageRequestId && _state.value.bitmap === bitmap) {
                    _state.value = _state.value.copy(
                        guidedBitmap = crop,
                        isPreparingZoom = false,
                        selectionMode = false,
                    )
                } else {
                    crop.recycle()
                }
            } catch (error: Exception) {
                if (requestId == pageRequestId) {
                    _state.value = _state.value.copy(isPreparingZoom = false, error = friendlyError(error))
                }
            }
        }
    }

    fun exitGuidedZoom() {
        _state.value = _state.value.copy(guidedBitmap = null, isRecognizing = false, recognizedText = null)
    }

    fun recognizeSelection() {
        val current = _state.value
        val bitmap = current.bitmap ?: return
        val rect = current.selection ?: current.guidedRegions.getOrNull(current.activeRegionIndex)?.bounds ?: return
        val cropToRead = current.guidedBitmap
        val requestId = ++recognitionRequestId
        val currentPageRequestId = pageRequestId
        viewModelScope.launch {
            _state.value = _state.value.copy(isRecognizing = true, error = null)
            var temporaryCrop: Bitmap? = null
            try {
                val target = cropToRead ?: withContext(Dispatchers.Default) { crop(bitmap, rect) }
                    .also { temporaryCrop = it }
                val result = recognizeText(target)
                if (requestId != recognitionRequestId || currentPageRequestId != pageRequestId) return@launch
                _state.value = _state.value.copy(
                    isRecognizing = false,
                    selectionMode = false,
                    recognizedText = result.ifBlank { "Não encontrei texto nessa região." },
                )
            } catch (error: Exception) {
                if (requestId == recognitionRequestId && currentPageRequestId == pageRequestId) {
                    _state.value = _state.value.copy(
                        isRecognizing = false,
                        error = "Não foi possível reconhecer o texto. Tente selecionar uma área maior.",
                    )
                }
            } finally {
                temporaryCrop?.recycle()
            }
        }
    }

    private fun loadPage(index: Int) {
        val currentDocument = pageDocument ?: return
        val currentUri = sourceUri ?: return
        pageJob?.cancel()
        adjacentPagesJob?.cancel()
        adjacentSpreadsJob?.cancel()
        spreadPageJob?.cancel()
        val requestId = ++pageRequestId
        pageJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                selectionMode = false,
                selection = null,
                guidedRegions = emptyList(),
                activeRegionIndex = -1,
                isAnalyzingCurrentPage = false,
                guidedBitmap = null,
                isPreparingZoom = false,
                isRecognizing = false,
                recognizedText = null,
                error = null,
            )
            recognitionRequestId++
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val bytes = currentDocument.readPage(index)
                    decodePage(bytes)
                }
                val fingerprint = documentFingerprint
                val detectedRegions = if (fingerprint != null) {
                    withContext(Dispatchers.IO) {
                        analysisStore.read(fingerprint, OrtBubbleDetector.VERSION, index)
                    }
                } else {
                    null
                }
                val regions = detectedRegions?.let { detected ->
                    GuidedPage(detected).orderedRegions(_state.value.readingDirection)
                }
                if (requestId != pageRequestId || sourceUri != currentUri) {
                    bitmap.recycle()
                    return@launch
                }
                getApplication<Application>().readerPreferences.edit { it[LastPageIndex] = index }
                _state.value = _state.value.copy(
                    isLoading = false,
                    pageIndex = index,
                    lastReadPage = index,
                    bitmap = bitmap,
                    previousPageBitmap = null,
                    nextPageBitmap = null,
                    previousPageRegions = emptyList(),
                    nextPageRegions = emptyList(),
                    spreadPageIndex = null,
                    spreadPageBitmap = null,
                    spreadPageRegions = emptyList(),
                    previousSpreadImages = null,
                    nextSpreadImages = null,
                    guidedRegions = regions.orEmpty(),
                    activeRegionIndex = when {
                        regions.isNullOrEmpty() -> -1
                        focusLastRegionOnPageLoad -> regions.lastIndex
                        else -> preferredRegionIndex.coerceIn(0, regions.lastIndex)
                    },
                    isAnalyzingCurrentPage = regions == null,
                )
                currentComicId?.let { comicId ->
                    val loadedState = _state.value
                    libraryRepository.recordOpened(
                        comicId,
                        loadedState.title ?: "Quadrinho",
                        loadedState.pageCount,
                        index,
                        loadedState.guidedRegions.getOrNull(loadedState.activeRegionIndex)?.id,
                    )
                }
                loadAdjacentPages(index, currentDocument, currentUri, requestId)
                if (regions != null) {
                    preferredRegionIndex = _state.value.activeRegionIndex.coerceAtLeast(0)
                    focusLastRegionOnPageLoad = false
                }
            } catch (error: Exception) {
                if (requestId == pageRequestId && sourceUri == currentUri) {
                    _state.value = _state.value.copy(isLoading = false, error = friendlyError(error))
                }
            } finally {
                if (requestId == pageRequestId) pageJob = null
            }
        }
    }

    private fun loadAdjacentPages(pageIndex: Int, currentDocument: PageDocument, uri: Uri, requestId: Long) {
        adjacentPagesJob?.cancel()
        adjacentPagesJob = viewModelScope.launch(Dispatchers.IO) {
            var previous: Bitmap? = null
            var next: Bitmap? = null
            suspend fun decodeNeighbor(index: Int): Bitmap? {
                if (index !in 0 until currentDocument.pageCount) return null
                val bytes = currentDocument.readPage(index, maxDimension = 1600, maxPixels = 2_560_000L)
                return withContext(Dispatchers.Default) {
                    decodePage(bytes, maxDimension = 1600, maxPixels = 2_560_000L)
                }
            }

            try {
                previous = decodeNeighbor(pageIndex - 1)
                next = decodeNeighbor(pageIndex + 1)
                val fingerprint = documentFingerprint
                val previousRegions = if (fingerprint != null && pageIndex > 0) {
                    analysisStore.read(fingerprint, OrtBubbleDetector.VERSION, pageIndex - 1)
                        ?.let { GuidedPage(it).orderedRegions(_state.value.readingDirection) }.orEmpty()
                } else emptyList()
                val nextRegions = if (fingerprint != null && pageIndex + 1 < currentDocument.pageCount) {
                    analysisStore.read(fingerprint, OrtBubbleDetector.VERSION, pageIndex + 1)
                        ?.let { GuidedPage(it).orderedRegions(_state.value.readingDirection) }.orEmpty()
                } else emptyList()
                if (requestId != pageRequestId || sourceUri != uri || _state.value.pageIndex != pageIndex) {
                    return@launch
                }
                _state.value = _state.value.copy(
                    previousPageBitmap = previous,
                    nextPageBitmap = next,
                    previousPageRegions = previousRegions,
                    nextPageRegions = nextRegions,
                )
                previous = null
                next = null
            } finally {
                previous?.takeUnless { it.isRecycled }?.recycle()
                next?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val app = getApplication<Application>()
        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Quadrinho"
    }

    private fun closePageDocument() {
        val current = pageDocument
        pageDocument = null
        runCatching { current?.close() }
    }

    private suspend fun materializeArchive(uri: Uri, reuseCache: Boolean): MaterializedArchive {
        if (reuseCache && cachedArchiveUri == uri && cachedArchiveFile?.isFile == true) {
            return MaterializedArchive(cachedArchiveFile!!, cachedArchiveFingerprint!!)
        }
        val app = getApplication<Application>()
        val directory = File(app.cacheDir, "comic-archives").apply {
            if (!exists() && !mkdirs()) throw IllegalStateException("Não foi possível preparar o cache do quadrinho.")
        }
        val fileName = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) } + ".cbz"
        val target = File(directory, fileName)
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            val coroutineContext = currentCoroutineContext()
            val contentDigest = MessageDigest.getInstance("SHA-256")
            val input = app.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("O Android não conseguiu abrir este arquivo.")
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_DOCUMENT_BYTES) {
                            throw IllegalArgumentException("O arquivo excede o limite de 512 MiB.")
                        }
                        output.write(buffer, 0, count)
                        contentDigest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (target.exists() && !target.delete()) throw IllegalStateException("Não foi possível atualizar o cache do quadrinho.")
            if (!temporary.renameTo(target)) throw IllegalStateException("Não foi possível guardar o arquivo para leitura.")
            directory.listFiles()?.filter { it != target }?.forEach(File::delete)
            return MaterializedArchive(
                file = target,
                fingerprint = contentDigest.digest().joinToString("") { byte -> "%02x".format(byte) },
            )
        } finally {
            temporary.delete()
        }
    }

    private fun friendlyError(error: Exception): String = when (error) {
        is SecurityException -> "O acesso ao arquivo expirou. Selecione o quadrinho novamente."
        else -> error.message ?: "Não foi possível abrir este arquivo."
    }

    private fun decodePage(bytes: ByteArray): Bitmap {
        return decodePage(bytes, maxDimension = 4096, maxPixels = 12_000_000L)
    }

    private fun decodeAnalysisPage(bytes: ByteArray): Bitmap =
        decodePage(bytes, maxDimension = 1600, maxPixels = 2_560_000L)

    private fun decodePage(bytes: ByteArray, maxDimension: Int, maxPixels: Long): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "A página não é uma imagem válida." }

        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension ||
            (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, options)
            ?: throw IllegalArgumentException("A página não pôde ser decodificada.")
    }

    private fun crop(bitmap: Bitmap, rect: NormalizedRect): Bitmap {
        val left = (rect.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (rect.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (rect.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (rect.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private suspend fun recognizeText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val closed = AtomicBoolean(false)
            fun closeRecognizer() {
                if (closed.compareAndSet(false, true)) recognizer.close()
            }
            val task: Task<com.google.mlkit.vision.text.Text> =
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
            task.addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result.text)
            }
            task.addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            task.addOnCompleteListener {
                closeRecognizer()
            }
            continuation.invokeOnCancellation {
                closeRecognizer()
            }
        }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 512L * 1024 * 1024
    }
}
