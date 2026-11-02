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
import com.estante.reader.core.archive.PageDocument
import com.estante.reader.core.archive.PageDocumentFactory
import com.estante.reader.core.analysis.OrtBubbleDetector
import com.estante.reader.core.data.ComicDatabase
import com.estante.reader.core.data.ComicEntity
import com.estante.reader.core.data.ComicImportState
import com.estante.reader.core.data.ComicLibraryRepository
import com.estante.reader.core.guided.GuidedRegion
import com.estante.reader.core.guided.GuidedPage
import com.estante.reader.core.guided.NormalizedRect
import com.estante.reader.core.guided.PageAnalysisStore
import com.estante.reader.core.guided.ReadingDirection
import com.estante.reader.core.reader.PageDisplayMode
import com.estante.reader.core.reader.ReaderSpread
import com.estante.reader.core.reader.ReaderSpreadBuilder
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
    val lastReadTitle: String? = null,
    val lastReadPage: Int = 0,
    val pageCount: Int = 0,
    val pageIndex: Int = 0,
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val pageDisplayMode: PageDisplayMode = PageDisplayMode.AUTOMATIC,
    val separateCover: Boolean = true,
    val panoramasAlone: Boolean = true,
    val libraryStyleId: String = LibraryStyle.default.id,
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
)

class EstanteViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    private val libraryRepository = ComicLibraryRepository(ComicDatabase.getInstance(application).comicDao())
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
    private var analysisJob: Job? = null
    private var focusLastRegionOnPageLoad = false
    private var preferredRegionIndex = 0
    private val analysisStore = PageAnalysisStore(application)
    private var pageRequestId = 0L
    private var openRequestId = 0L
    private var recognitionRequestId = 0L

    init {
        viewModelScope.launch {
            libraryRepository.observeLibrary().collect { comics ->
                _libraryItems.value = comics.map { comic ->
                    LibraryComicUi(
                        id = comic.id,
                        title = comic.title,
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
            _state.value = _state.value.copy(
                readingDirection = direction,
                pageDisplayMode = displayMode,
                separateCover = preferences[SeparateCoverPreference] ?: true,
                panoramasAlone = preferences[PanoramasAlonePreference] ?: true,
                libraryStyleId = libraryStyle.id,
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
                    if (existing != null) return@withContext ImportResult(existing, null)
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
                    ImportResult(comic, openedDocument)
                }
                staging?.delete()
                openComic(imported.comic.id, imported.document)
            } catch (error: Exception) {
                preparedDocument?.document?.close()
                staging?.delete()
                if (rowCreated) recordId?.let { id -> runCatching { libraryRepository.markFailed(id, friendlyError(error)) } }
                _state.value = _state.value.copy(isImporting = false, isLoading = false, error = friendlyError(error))
            }
        }
    }

    private data class ImportResult(val comic: ComicEntity, val document: OpenedPageDocument?)

    fun openComic(id: String) = openComic(id, null)

    private fun openComic(id: String, preparedDocument: OpenedPageDocument?) {
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
                    lastReadTitle = comic.title,
                    pageCount = indexed.pageCount,
                    pageIndex = page,
                    lastReadPage = page,
                    libraryStyleId = _state.value.libraryStyleId,
                    readingDirection = _state.value.readingDirection,
                    pageDisplayMode = comicDisplayMode,
                    separateCover = comicSeparateCover,
                    panoramasAlone = comicPanoramasAlone,
                    isAnalyzing = cachedPages < indexed.pageCount,
                    analyzedPages = cachedPages,
                    analysisPageCount = indexed.pageCount,
                    isLoading = true,
                )
                libraryRepository.recordOpened(id, comic.title, indexed.pageCount, page, comic.lastRegionId)
                loadPage(page)
                startAnalysis(indexed, Uri.fromFile(file), fingerprint)
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
            if (currentComicId == id) closeDocument()
            libraryRepository.remove(id)
            comic.managedPath?.let { File(it).delete() }
            comic.coverPath?.let { File(getApplication<Application>().filesDir, it).delete() }
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
            if (!focusLastRegionOnPageLoad) preferredRegionIndex = 0
            loadPage(index)
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
        _state.value = _state.value.copy(guidedModeEnabled = enabled)
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
