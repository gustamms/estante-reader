package com.estante.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.estante.reader.core.guided.GuidedRegion
import com.estante.reader.core.guided.NormalizedRect
import com.estante.reader.core.reader.PageDisplayMode
import com.estante.reader.core.reader.ReaderSpread
import com.estante.reader.core.reader.ReaderSpreadBuilder
import com.estante.reader.core.publication.PublicationBookmark
import com.estante.reader.core.archive.PdfPageDocument
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.PageCurlState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

private val Ink = Color(0xFF292722)
private val Paper = Color(0xFFF6F2EA)
private val Terracotta = Color(0xFFA95138)
private val Olive = Color(0xFF686B4C)
private val Muted = Color(0xFF817D73)

data class LibraryComicUi(
    val id: String,
    val title: String,
    val formatId: String,
    val coverPath: String?,
    val pageIndex: Int,
    val pageCount: Int,
    val lastOpenedAt: Long = 0L,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Terracotta,
                    secondary = Olive,
                    background = Paper,
                    surface = Color.White,
                    onPrimary = Color.White,
                    onBackground = Ink,
                    onSurface = Ink,
                ),
            ) {
                EstanteApp()
            }
        }
    }
}

@Composable
private fun EstanteApp(viewModel: EstanteViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val comics by viewModel.libraryItems.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::openDocument) }

    BackHandler(enabled = showSettings) { showSettings = false }

    if (state.title != null && state.pageCount > 0) {
        if (state.publicationRootPath != null) {
            BookReaderScreen(
                title = state.title.orEmpty(),
                author = state.publicationAuthor,
                bookId = state.publicationId.orEmpty(),
                rootPath = state.publicationRootPath.orEmpty(),
                chapters = state.publicationChapters,
                chapterIndex = state.pageIndex,
                progression = state.bookProgress,
                fontSizeSp = state.bookFontSizeSp,
                lineSpacing = state.bookLineSpacing,
                marginsDp = state.bookMarginsDp,
                columnMode = state.bookColumnMode,
                background = ReaderCanvasBackground.fromId(state.readerCanvasBackgroundId),
                readingDirection = state.readingDirection,
                capabilities = state.publicationCapabilities,
                bookmarks = state.publicationBookmarks,
                highlights = state.publicationHighlights,
                highlightColorId = state.bookHighlightColorId,
                pendingHighlight = state.pendingBookHighlight,
                searchResults = state.publicationSearchResults,
                isSearching = state.isSearchingPublication,
                onClose = viewModel::closeDocument,
                onProgress = viewModel::recordBookProgress,
                onFontSizeChange = viewModel::setBookFontSize,
                onLineSpacingChange = viewModel::setBookLineSpacing,
                onMarginsChange = viewModel::setBookMargins,
                onColumnsChange = viewModel::setBookColumnMode,
                onBackgroundChange = viewModel::setReaderCanvasBackground,
                onAddBookmark = viewModel::addBookBookmark,
                onTextSelection = { expectedBookId, href, text, start, end, prefix, suffix, onResult ->
                    viewModel.addBookHighlight(expectedBookId, href, text, start, end, prefix, suffix, onResult)
                },
                onHighlightColorChange = viewModel::setBookHighlightColor,
                onHighlightItemColorChange = viewModel::changeBookHighlightColor,
                onOpenHighlight = viewModel::openBookHighlight,
                onRemoveHighlight = viewModel::removeBookHighlight,
                onRestoreHighlight = viewModel::restoreBookHighlight,
                onPendingHighlightConsumed = viewModel::clearPendingBookHighlight,
                onSearch = viewModel::searchPublication,
                onOpenSearchResult = { result -> viewModel.recordBookProgress(result.chapterIndex, result.progression) },
                onToggleDirection = {
                    viewModel.setReadingDirection(
                        if (state.readingDirection == com.estante.reader.core.guided.ReadingDirection.LEFT_TO_RIGHT) {
                            com.estante.reader.core.guided.ReadingDirection.RIGHT_TO_LEFT
                        } else com.estante.reader.core.guided.ReadingDirection.LEFT_TO_RIGHT,
                    )
                },
            )
        } else ComicReaderScreen(
            state = state,
            onClose = viewModel::closeDocument,
            onPageChange = viewModel::goToPage,
            onSelectRegion = viewModel::setSelectionMode,
            onRegionSelected = viewModel::setSelection,
            onZoom = viewModel::createGuidedZoom,
            onExitZoom = viewModel::exitGuidedZoom,
            onRecognize = viewModel::recognizeSelection,
            regions = state.guidedRegions,
            activeRegionIndex = state.activeRegionIndex,
            isGuidedMode = state.guidedModeEnabled,
            isAnalysisRunning = state.isAnalyzing,
            analyzedPageCount = state.analyzedPages,
            totalAnalysisPageCount = state.analysisPageCount,
            onSetGuidedMode = viewModel::setGuidedModeEnabled,
            onPreviousRegion = viewModel::previousRegion,
            onNextRegion = viewModel::nextRegion,
            onChooseRegion = viewModel::focusRegion,
            onToggleDirection = {
                viewModel.setReadingDirection(
                    if (state.readingDirection == com.estante.reader.core.guided.ReadingDirection.LEFT_TO_RIGHT) {
                        com.estante.reader.core.guided.ReadingDirection.RIGHT_TO_LEFT
                    } else {
                        com.estante.reader.core.guided.ReadingDirection.LEFT_TO_RIGHT
                    },
                )
            },
            onSetPageDisplayMode = viewModel::setPageDisplayMode,
            onSetSeparateCover = viewModel::setSeparateCover,
            onSetPanoramasAlone = viewModel::setPanoramasAlone,
            onSetReaderCanvasBackground = viewModel::setReaderCanvasBackground,
            onLoadSpreadPage = viewModel::loadSpreadPage,
            onLoadNeighborSpreads = viewModel::loadNeighborSpreads,
            onAddPageBookmark = viewModel::addPageBookmark,
            onRemovePageBookmark = viewModel::removePageBookmark,
            onSearchPdfText = viewModel::searchPdfText,
            onOpenPdfTextSearchResult = viewModel::openPdfTextSearchResult,
        )
    } else if (showSettings) {
        LibraryAppearanceScreen(
            selectedStyleId = state.libraryStyleId,
            onApplyStyle = viewModel::setLibraryStyle,
            onBack = { showSettings = false },
        )
    } else {
        LibraryScreen(
            state = state,
            onImport = { documentPicker.launch(arrayOf("*/*")) },
            comics = comics,
            onOpenComic = viewModel::openComic,
            onDeleteComic = viewModel::removeComic,
            onOpenSettings = { showSettings = true },
        )
    }
}

@Composable
private fun LibraryScreen(
    state: ReaderUiState,
    onImport: () -> Unit,
    comics: List<LibraryComicUi>,
    onOpenComic: (String) -> Unit,
    onDeleteComic: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var comicToDelete by remember { mutableStateOf<LibraryComicUi?>(null) }
    var query by remember { mutableStateOf("") }
    val style = LibraryStyle.fromId(state.libraryStyleId)
    val filteredComics = remember(comics, query) {
        if (query.isBlank()) comics else comics.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }
    val recentlyRead = remember(comics) { comics.maxByOrNull { it.lastOpenedAt } }
    Surface(modifier = Modifier.fillMaxSize(), color = style.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            LibraryHeader(
                style = style,
                isImporting = state.isImporting,
                importEnabled = !state.isLoading && !state.isImporting,
                onImport = onImport,
                onOpenSettings = onOpenSettings,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Buscar na estante", color = style.muted) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = style.ink),
                shape = RoundedCornerShape(if (style.layout == LibraryLayout.COMICS) 8.dp else 16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = style.surface,
                    unfocusedContainerColor = style.surface,
                    focusedIndicatorColor = style.accent,
                    unfocusedIndicatorColor = style.shelf,
                    focusedTextColor = style.ink,
                    unfocusedTextColor = style.ink,
                    cursorColor = style.accent,
                ),
            )

            state.error?.let { ErrorMessage(it) }
            if (state.isImporting) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = style.accent, strokeWidth = 2.dp)
                    Text("  Preparando arquivo…", color = style.muted, fontSize = 13.sp)
                }
            }
            if (state.isLoading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = style.accent)
                }
            } else if (comics.isEmpty()) {
                EmptyLibrary(style = style, onImport = onImport, modifier = Modifier.weight(1f))
            } else if (filteredComics.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Nenhum livro encontrado", color = style.muted)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val columnCount = (maxWidth / 148.dp).toInt().coerceIn(2, 6)
                    val rows = filteredComics.chunked(columnCount)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (query.isBlank() && recentlyRead != null) {
                            item(key = "continue-reading") {
                                ContinueReadingCard(
                                    comic = recentlyRead,
                                    style = style,
                                    onClick = { onOpenComic(recentlyRead.id) },
                                )
                            }
                        }
                        item(key = "library-heading") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(
                                        if (query.isBlank()) "Minha biblioteca" else "Resultados",
                                        color = style.ink,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default,
                                    )
                                    Text("${filteredComics.size} ${if (filteredComics.size == 1) "livro" else "livros e quadrinhos"}", color = style.muted, fontSize = 12.sp)
                                }
                                Text("CAPAS", color = style.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            }
                        }
                        items(rows, key = { row -> "row-${row.first().id}" }) { row ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    row.forEach { comic ->
                                        LibraryBookCard(
                                            comic = comic,
                                            style = style,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onOpenComic(comic.id) },
                                            onLongClick = { comicToDelete = comic },
                                        )
                                    }
                                    repeat(columnCount - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                                if (style.layout == LibraryLayout.WOODEN_SHELVES) {
                                    ShelfBoard(style = style)
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    comicToDelete?.let { comic ->
        AlertDialog(
            onDismissRequest = { comicToDelete = null },
            title = { Text("Remover da estante?") },
            text = { Text("“${comic.title}” será removido da biblioteca deste aparelho.") },
            confirmButton = {
                TextButton(onClick = { onDeleteComic(comic.id); comicToDelete = null }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { comicToDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LibraryHeader(
    style: LibraryStyle,
    isImporting: Boolean,
    importEnabled: Boolean,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val headerBackground = if (style == LibraryStyle.BLUE_EDITORIAL) Color(0xFF20364F) else style.background
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 430.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (style.layout == LibraryLayout.COMICS) 10.dp else 20.dp))
                .background(headerBackground)
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.estante_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("ESTANTE", color = if (style == LibraryStyle.BLUE_EDITORIAL) Color(0xFFD8C59B) else style.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(
                        "Um lugar para ler",
                        color = if (style == LibraryStyle.BLUE_EDITORIAL) Color.White else style.ink,
                        fontSize = if (compact) 16.sp else 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default,
                        maxLines = 1,
                    )
                }
            }
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 12.dp, vertical = 6.dp),
                modifier = Modifier.semantics { contentDescription = "Abrir configurações de aparência" },
            ) {
                Text("⚙", color = if (style == LibraryStyle.BLUE_EDITORIAL) Color.White else style.ink, fontSize = 18.sp)
                if (!compact) Text("Aparência", color = if (style == LibraryStyle.BLUE_EDITORIAL) Color.White else style.ink, fontSize = 12.sp)
            }
            Button(
                onClick = onImport,
                enabled = importEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = style.accent, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 14.dp, vertical = 10.dp),
                modifier = Modifier.semantics { contentDescription = if (isImporting) "Importando arquivo" else "Adicionar livro ou quadrinho" },
            ) {
                Text(if (isImporting) "…" else if (compact) "+" else "+  Adicionar", maxLines = 1, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(style: LibraryStyle, onImport: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("▤", color = style.accent, fontSize = 48.sp)
            Spacer(Modifier.height(10.dp))
            Text("Sua estante está esperando", color = style.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default)
            Spacer(Modifier.height(6.dp))
            Text("Adicione livros EPUB ou MOBI, ou quadrinhos em PDF, CBZ ou CBR para começar.", color = style.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = style.accent)) { Text("Adicionar à estante") }
        }
    }
}

@Composable
private fun ContinueReadingCard(comic: LibraryComicUi, style: LibraryStyle, onClick: () -> Unit) {
    val progress = if (comic.pageCount > 0) (comic.pageIndex + 1).toFloat() / comic.pageCount else 0f
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(if (style.layout == LibraryLayout.COMICS) 10.dp else 20.dp),
        color = style.surface,
        shadowElevation = if (style == LibraryStyle.MINIMAL) 0.dp else 2.dp,
        border = androidx.compose.foundation.BorderStroke(if (style.layout == LibraryLayout.COMICS) 2.dp else 1.dp, style.shelf.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(if (style.layout == LibraryLayout.GALLERY) 84.dp else 62.dp)
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(style.coverSurface),
                contentAlignment = Alignment.Center,
            ) { LibraryCover(comic.coverPath, comic.title, style) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("CONTINUAR LENDO", color = style.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(5.dp))
                Text(comic.title, color = style.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default)
                Spacer(Modifier.height(5.dp))
                val positionName = if (comic.formatId == "EPUB" || comic.formatId == "MOBI") "Capítulo" else "Página"
                Text(if (comic.pageCount > 0) "$positionName ${(comic.pageIndex + 1).coerceAtMost(comic.pageCount)} de ${comic.pageCount}" else "Abrir leitura", color = style.muted, fontSize = 12.sp)
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(style.shelf.copy(alpha = 0.35f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(style.accent))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("›", color = style.accent, fontSize = 28.sp)
        }
    }
}

@Composable
private fun LibraryBookCard(
    comic: LibraryComicUi,
    style: LibraryStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val comicStyle = style.layout == LibraryLayout.COMICS
    Column(
        modifier
            .clip(RoundedCornerShape(if (comicStyle) 8.dp else 14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(if (style.layout == LibraryLayout.WOODEN_SHELVES) 2.dp else 5.dp),
        horizontalAlignment = if (style.layout == LibraryLayout.WOODEN_SHELVES) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(if (comicStyle) 4.dp else 10.dp))
                .background(style.coverSurface)
                .then(if (comicStyle) Modifier.border(2.dp, style.ink, RoundedCornerShape(4.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            LibraryCover(comic.coverPath, comic.title, style)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            comic.title,
            color = style.ink,
            maxLines = 2,
            fontSize = if (comicStyle) 13.sp else 12.sp,
            lineHeight = 15.sp,
            fontWeight = if (comicStyle) FontWeight.Bold else FontWeight.Medium,
            textAlign = if (style.layout == LibraryLayout.WOODEN_SHELVES) TextAlign.Center else TextAlign.Start,
            fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default,
        )
        if (comic.pageCount > 0 && style != LibraryStyle.MINIMAL) {
            Text("${(comic.pageIndex + 1).coerceAtMost(comic.pageCount)} / ${comic.pageCount}", color = style.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ShelfBoard(style: LibraryStyle) {
    val boardColors = if (style == LibraryStyle.NIGHT_LIBRARY) {
        listOf(Color(0xFF76573D), Color(0xFF4C3729), Color(0xFF38291F))
    } else {
        listOf(Color(0xFFD4AA76), Color(0xFFBC8953), Color(0xFF9B693C))
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 6.dp).height(9.dp)
            .background(Brush.verticalGradient(boardColors), RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)),
    )
}

@Composable
private fun LibraryAppearanceScreen(
    selectedStyleId: String,
    onApplyStyle: (String) -> Unit,
    onBack: () -> Unit,
) {
    var previewStyleId by remember(selectedStyleId) { mutableStateOf(selectedStyleId) }
    val previewStyle = LibraryStyle.fromId(previewStyleId)
    Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Color(0xFFF6F2EA)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹  Voltar", color = Ink) }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("CONFIGURAÇÕES", color = Olive, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Aparência da estante", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
                }
            }
            Spacer(Modifier.height(8.dp))
            StylePreviewCard(style = previewStyle, isApplied = previewStyleId == selectedStyleId)
            Spacer(Modifier.height(12.dp))
            Text("Escolha um estilo", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(LibraryStyle.entries, key = { it.id }) { style ->
                    StyleChoiceCard(
                        style = style,
                        selected = previewStyleId == style.id,
                        applied = selectedStyleId == style.id,
                        onClick = { previewStyleId = style.id },
                    )
                }
            }
            Button(
                onClick = { onApplyStyle(previewStyleId); onBack() },
                enabled = previewStyleId != selectedStyleId,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = previewStyle.accent, disabledContainerColor = Color(0xFFD7D1C6)),
            ) {
                Text(if (previewStyleId == selectedStyleId) "Este estilo já está aplicado" else "Usar ${previewStyle.title}")
            }
        }
    }
}

@Composable
private fun StylePreviewCard(style: LibraryStyle, isApplied: Boolean) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = style.background,
        shadowElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, style.shelf.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(style.title, color = style.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default)
                    Text(if (isApplied) "Em uso" else style.description, color = style.muted, fontSize = 12.sp)
                }
                if (style == LibraryStyle.default) Text("PADRÃO", color = style.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(10.dp))
            StyleMiniature(style = style, modifier = Modifier.fillMaxWidth().height(70.dp))
        }
    }
}

@Composable
private fun StyleChoiceCard(style: LibraryStyle, selected: Boolean, applied: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (style.layout == LibraryLayout.COMICS) 8.dp else 14.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "${style.title}. ${style.description}. ${if (applied) "Estilo em uso." else if (selected) "Prévia selecionada." else "Toque para pré-visualizar."}"
        }.clickable(role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = style.background,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) style.accent else style.shelf.copy(alpha = 0.55f)),
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        Column(Modifier.padding(9.dp)) {
            StyleMiniature(style = style, modifier = Modifier.fillMaxWidth().height(58.dp))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(style.title, color = style.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default)
                Text(if (applied) "✓" else if (selected) "●" else "", color = style.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (style == LibraryStyle.default) Text("Padrão", color = style.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StyleMiniature(style: LibraryStyle, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(style.surface)) {
        if (style == LibraryStyle.BLUE_EDITORIAL) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.30f).background(Color(0xFF20364F)))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ESTANTE", color = if (style == LibraryStyle.BLUE_EDITORIAL) Color.White else style.accent, fontSize = 6.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                Text("Minha biblioteca", color = style.ink, fontSize = 7.sp, fontWeight = FontWeight.SemiBold, fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default)
                Text("•••", color = style.muted, fontSize = 6.sp)
            }
            Spacer(Modifier.height(3.dp))
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(if (style.layout == LibraryLayout.GALLERY) 5.dp else 3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                listOf(Color(0xFF304E4B), Color(0xFFEEE4CF), Color(0xFF9D3D34), Color(0xFF26324B)).forEachIndexed { index, coverColor ->
                    val featured = style.layout == LibraryLayout.GALLERY && index == 0
                    Box(
                        Modifier.weight(if (featured) 1.25f else 1f)
                            .fillMaxHeight(if (featured) 0.98f else 0.82f)
                            .clip(RoundedCornerShape(if (style.layout == LibraryLayout.COMICS) 2.dp else 3.dp))
                            .background(coverColor)
                            .then(if (style.layout == LibraryLayout.COMICS) Modifier.border(1.dp, style.ink) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (index == 1) Box(Modifier.fillMaxWidth(0.52f).fillMaxHeight(0.5f).background(Color(0xFFB89058), RoundedCornerShape(20.dp)))
                    }
                }
            }
            if (style.layout == LibraryLayout.WOODEN_SHELVES) {
                Box(Modifier.fillMaxWidth().height(5.dp).background(style.shelf))
            } else {
                Box(Modifier.fillMaxWidth().height(1.dp).background(style.shelf.copy(alpha = 0.65f)))
            }
        }
    }
}

@Composable
private fun LibraryCover(path: String?, title: String, style: LibraryStyle) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            path?.let { filePath ->
                val file = File(filePath)
                if (!file.isFile) return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(filePath, bounds)
                val sample = generateSequence(1) { it * 2 }
                    .takeWhile { bounds.outWidth / it > 640 || bounds.outHeight / it > 900 }
                    .lastOrNull() ?: 1
                BitmapFactory.decodeFile(filePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }?.asImageBitmap()
    }
    if (image != null) {
        Image(image!!, contentDescription = "Capa de $title", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(title.take(1).uppercase(), color = style.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default)
            Spacer(Modifier.height(5.dp))
            Text(title, color = style.ink, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 4, textAlign = TextAlign.Center, fontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default)
        }
    }
}

@Composable
@OptIn(ExperimentalPageCurlApi::class)
private fun ComicReaderScreen(
    state: ReaderUiState,
    onClose: () -> Unit,
    onPageChange: (Int) -> Unit,
    onSelectRegion: (Boolean) -> Unit,
    onRegionSelected: (NormalizedRect) -> Unit,
    onZoom: () -> Unit,
    onExitZoom: () -> Unit,
    onRecognize: () -> Unit,
    regions: List<GuidedRegion> = emptyList(),
    activeRegionIndex: Int = -1,
    isGuidedMode: Boolean = false,
    isAnalysisRunning: Boolean = false,
    analyzedPageCount: Int = 0,
    totalAnalysisPageCount: Int = 0,
    onSetGuidedMode: (Boolean) -> Unit = {},
    onPreviousRegion: () -> Unit = {},
    onNextRegion: () -> Unit = {},
    onChooseRegion: (Int) -> Unit = {},
    onToggleDirection: () -> Unit = {},
    onSetPageDisplayMode: (PageDisplayMode) -> Unit = {},
    onSetSeparateCover: (Boolean) -> Unit = {},
    onSetPanoramasAlone: (Boolean) -> Unit = {},
    onSetReaderCanvasBackground: (ReaderCanvasBackground) -> Unit = {},
    onLoadSpreadPage: (Int?) -> Unit = {},
    onLoadNeighborSpreads: (Int?, Int?) -> Unit = { _, _ -> },
    onAddPageBookmark: () -> Unit = {},
    onRemovePageBookmark: (PublicationBookmark) -> Unit = {},
    onSearchPdfText: (String) -> Unit = {},
    onOpenPdfTextSearchResult: (PdfPageDocument.TextSearchResult) -> Unit = {},
) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val context = LocalContext.current
    val view = LocalView.current
    val window = (context as? Activity)?.window
    var controlsVisible by remember(state.title) { mutableStateOf(false) }
    var pageBookmarksVisible by remember(state.title) { mutableStateOf(false) }
    var pdfSearchVisible by remember(state.title) { mutableStateOf(false) }
    var pdfSearchQuery by remember(state.title) { mutableStateOf("") }
    var lensVisible by remember(state.title, state.pageIndex) { mutableStateOf(false) }
    var lensPageIndex by remember(state.title, state.pageIndex) { mutableIntStateOf(state.pageIndex) }
    var companionActiveRegion by remember(state.title, state.pageIndex) { mutableIntStateOf(0) }
    var zoomedPages by remember(state.title) { mutableStateOf(emptySet<Int>()) }
    val pageTurnScope = rememberCoroutineScope()
    BackHandler {
        when {
            pdfSearchVisible -> pdfSearchVisible = false
            pageBookmarksVisible -> pageBookmarksVisible = false
            controlsVisible -> controlsVisible = false
            lensVisible -> lensVisible = false
            else -> onClose()
        }
    }
    DisposableEffect(window, view) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val readerBackground = ReaderCanvasBackground.fromId(state.readerCanvasBackgroundId)
    Surface(modifier = Modifier.fillMaxSize(), color = readerBackground.color) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageViewportWidth = maxWidth
            val pageViewportHeight = maxHeight
            val requestedDoublePage = when (state.pageDisplayMode) {
                PageDisplayMode.SINGLE_PAGE -> false
                PageDisplayMode.DOUBLE_PAGE -> true
                PageDisplayMode.AUTOMATIC -> maxWidth >= 680.dp
            }
            val bitmap = state.bitmap
            val baseSpread = ReaderSpreadBuilder.forPage(state.pageIndex, state.pageCount, state.separateCover)
            val companionIndex = baseSpread?.let { spread ->
                when (state.pageIndex) {
                    spread.first -> spread.second
                    spread.second -> spread.first
                    else -> null
                }
            }
            LaunchedEffect(state.pageIndex, requestedDoublePage, companionIndex, state.selectionMode) {
                onLoadSpreadPage(if (requestedDoublePage && !state.selectionMode) companionIndex else null)
            }
            val companionBitmap = state.spreadPageBitmap.takeIf { state.spreadPageIndex == companionIndex }
            val companionRegions = state.spreadPageRegions.takeIf { state.spreadPageIndex == companionIndex }.orEmpty()
            val currentPanorama = bitmap?.let { it.width.toFloat() / it.height >= 1.30f } == true
            val companionPanorama = companionBitmap?.let { it.width.toFloat() / it.height >= 1.30f } == true
            val panoramaSolo = state.panoramasAlone && (currentPanorama || companionPanorama)
            val displaySpread = if (panoramaSolo) ReaderSpread(state.pageIndex, null) else baseSpread
            val spreadMode = requestedDoublePage && !state.selectionMode && !panoramaSolo && displaySpread?.second != null
            val doublePage = spreadMode && companionBitmap != null
            val previousSpreadPage = if (panoramaSolo && baseSpread != null) {
                if (state.pageIndex == baseSpread.second) companionIndex
                else ReaderSpreadBuilder.previous(baseSpread, state.pageCount, state.separateCover)
            } else displaySpread?.let { ReaderSpreadBuilder.previous(it, state.pageCount, state.separateCover) }
            val nextSpreadPage = if (panoramaSolo && baseSpread != null) {
                if (state.pageIndex == baseSpread.first) companionIndex
                else ReaderSpreadBuilder.next(baseSpread, state.pageCount, state.separateCover)
            } else displaySpread?.let { ReaderSpreadBuilder.next(it, state.pageCount, state.separateCover) }
            val pageTurnState = remember(state.title, state.pageIndex, doublePage) {
                PageCurlState(initialMax = 3, initialCurrent = 1)
            }
            val onZoomStateChange: (Int, Boolean) -> Unit = { page, zoomed ->
                zoomedPages = if (zoomed) zoomedPages + page else zoomedPages - page
            }
            val isLeftToRight = state.readingDirection == com.estante.reader.core.guided.ReadingDirection.LEFT_TO_RIGHT
            val curlDragInteraction = readerPageCurlDragInteraction(state.readingDirection)
            val canTurnBackward = if (doublePage) {
                previousSpreadPage != null && state.previousSpreadImages != null
            } else {
                state.pageIndex > 0 && state.previousPageBitmap != null
            }
            val canTurnForward = if (doublePage) {
                nextSpreadPage != null && state.nextSpreadImages != null
            } else {
                state.pageIndex + 1 < state.pageCount && state.nextPageBitmap != null
            }
            val pageTurnInteractionEnabled = !state.isLoading && !state.selectionMode &&
                !lensVisible && !controlsVisible &&
                state.pageIndex !in zoomedPages && (!doublePage || companionIndex !in zoomedPages)
            val pageCurlConfig = rememberReaderPageCurlConfig(
                backgroundColor = readerBackground.color,
                readingDirection = state.readingDirection,
                dragForwardEnabled = pageTurnInteractionEnabled && canTurnForward,
                dragBackwardEnabled = pageTurnInteractionEnabled && canTurnBackward,
            )
            SideEffect {
                pageCurlConfig.dragInteraction = curlDragInteraction
                pageCurlConfig.dragBackwardEnabled = pageTurnInteractionEnabled && canTurnBackward
                pageCurlConfig.dragForwardEnabled = pageTurnInteractionEnabled && canTurnForward
                pageCurlConfig.tapBackwardEnabled = false
                pageCurlConfig.tapForwardEnabled = false
                pageCurlConfig.tapCustomEnabled = false
            }
            LaunchedEffect(pageTurnState.current) {
                val target = when (pageTurnState.current) {
                    0 -> if (doublePage) previousSpreadPage else (state.pageIndex - 1).takeIf { it >= 0 }
                    2 -> if (doublePage) nextSpreadPage else (state.pageIndex + 1).takeIf { it < state.pageCount }
                    else -> null
                }
                if (target != null) {
                    lensVisible = false
                    onPageChange(target)
                } else if (pageTurnState.current != 1) {
                    pageTurnState.snapTo(1)
                }
            }
            LaunchedEffect(state.error) {
                if (state.error != null && pageTurnState.current != 1) pageTurnState.snapTo(1)
            }
            LaunchedEffect(state.pageIndex, spreadMode, previousSpreadPage, nextSpreadPage) {
                onLoadNeighborSpreads(
                    previousSpreadPage.takeIf { spreadMode },
                    nextSpreadPage.takeIf { spreadMode },
                )
            }
            if (bitmap != null) {
                val onTapBackground: (Offset, IntSize) -> Unit = { point, size ->
                    val zoomed = state.pageIndex in zoomedPages || (doublePage && companionIndex in zoomedPages)
                    val edgeDirection = ReaderInteraction.edgeTurnDirection(point.x, size.width.toFloat(), state.readingDirection)
                    when {
                        zoomed -> Unit
                        edgeDirection == 1 && pageTurnInteractionEnabled && canTurnForward -> {
                            lensVisible = false
                            pageTurnScope.launch { pageTurnState.next() }
                        }
                        edgeDirection == -1 && pageTurnInteractionEnabled && canTurnBackward -> {
                            lensVisible = false
                            pageTurnScope.launch { pageTurnState.prev() }
                        }
                        !state.selectionMode && ReaderInteraction.isCenterTap(point.x, point.y, size.width.toFloat(), size.height.toFloat()) -> {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) lensVisible = false
                        }
                        lensVisible || controlsVisible -> {
                            lensVisible = false
                            controlsVisible = false
                        }
                    }
                }
                PageCurl(
                    count = 3,
                    modifier = Modifier.fillMaxSize(),
                    state = pageTurnState,
                    config = pageCurlConfig,
                ) { turnPage ->
                    when (turnPage) {
                        0 -> if (doublePage) {
                            StaticSpreadPage(state.previousSpreadImages, state.readingDirection, readerBackground.color)
                        } else {
                            StaticComicPage(state.previousPageBitmap, backgroundColor = readerBackground.color)
                        }
                        2 -> if (doublePage) {
                            StaticSpreadPage(state.nextSpreadImages, state.readingDirection, readerBackground.color)
                        } else {
                            StaticComicPage(state.nextPageBitmap, backgroundColor = readerBackground.color)
                        }
                        else -> if (!doublePage) {
                            PageCanvas(
                                modifier = Modifier.fillMaxSize(),
                                bitmap = bitmap,
                                selectionMode = state.selectionMode,
                                selected = state.selection,
                                regions = regions,
                                activeRegionIndex = activeRegionIndex,
                                guidedMode = lensVisible && lensPageIndex == state.pageIndex,
                                onChooseRegion = { index ->
                                    lensPageIndex = state.pageIndex
                                    onChooseRegion(index)
                                    lensVisible = true
                                    controlsVisible = false
                                },
                                onRegionSelected = onRegionSelected,
                                onBackgroundTap = onTapBackground,
                                onLongPress = { controlsVisible = true; lensVisible = false },
                                onDismissLens = { lensVisible = false },
                                searchMatches = state.pageSearchMatches,
                                pageIndex = state.pageIndex,
                                onZoomStateChange = onZoomStateChange,
                            )
                        } else {
                            val spread = displaySpread
                            val companion = companionBitmap
                            val pageOrder = if (isLeftToRight) {
                                listOf(spread.first, spread.second)
                            } else {
                                listOf(spread.second, spread.first)
                            }
                            Row(Modifier.fillMaxSize()) {
                                pageOrder.forEachIndexed { pagePosition, pageIndex ->
                                    if (pagePosition == 1) Spacer(Modifier.width(20.dp))
                                    val isCurrent = pageIndex == state.pageIndex
                                    val pageBitmap = if (isCurrent) bitmap else companion
                                    val pageRegions = if (isCurrent) regions else companionRegions
                                    val pageActiveRegion = if (isCurrent) activeRegionIndex else companionActiveRegion
                                    val pageGapPx = with(LocalDensity.current) { 20.dp.toPx() }
                                    val wholeWidthPx = with(LocalDensity.current) { pageViewportWidth.toPx() }
                                    val wholeHeightPx = with(LocalDensity.current) { pageViewportHeight.toPx() }
                                    PageCanvas(
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        bitmap = pageBitmap,
                                        selectionMode = false,
                                        selected = null,
                                        regions = pageRegions,
                                        activeRegionIndex = pageActiveRegion,
                                        guidedMode = lensVisible && lensPageIndex == pageIndex,
                                        onChooseRegion = { index ->
                                            lensPageIndex = pageIndex
                                            if (isCurrent) onChooseRegion(index) else companionActiveRegion = index
                                            lensVisible = true
                                            controlsVisible = false
                                        },
                                        onRegionSelected = {},
                                        onBackgroundTap = { point, pageSize ->
                                            onTapBackground(
                                                Offset(pagePosition * (pageSize.width + pageGapPx) + point.x, point.y),
                                                IntSize(wholeWidthPx.toInt(), wholeHeightPx.toInt()),
                                            )
                                        },
                                        onLongPress = { controlsVisible = true; lensVisible = false },
                                        onDismissLens = { lensVisible = false },
                                        searchMatches = if (isCurrent) state.pageSearchMatches else emptyList(),
                                        pageIndex = pageIndex,
                                        onZoomStateChange = onZoomStateChange,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (state.isLoading && bitmap == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFFE4AA8D))
            }
            if (controlsVisible) {
                ReaderControlsOverlay(
                    state = state,
                    isGuidedMode = isGuidedMode,
                    activeRegionIndex = activeRegionIndex,
                    regionCount = regions.size,
                    isAnalysisRunning = isAnalysisRunning,
                    analyzedPageCount = analyzedPageCount,
                    totalAnalysisPageCount = totalAnalysisPageCount,
                    canTurnBackward = !state.isLoading && pageTurnState.progress == 0f && canTurnBackward,
                    canTurnForward = !state.isLoading && pageTurnState.progress == 0f && canTurnForward,
                    pageBookmarks = state.pageBookmarks,
                    canSearchPageText = state.documentFormatId == "PDF",
                    pageLabel = if (spreadMode) {
                        "Páginas ${displaySpread.first + 1}–${displaySpread.second + 1} de ${state.pageCount}"
                    } else "Página ${state.pageIndex + 1} de ${state.pageCount}",
                    onCloseControls = { controlsVisible = false },
                    onCloseReader = onClose,
                    onPageChange = onPageChange,
                    onPreviousPage = {
                        if (canTurnBackward) {
                            lensVisible = false
                            pageTurnScope.launch { pageTurnState.prev() }
                        }
                    },
                    onNextPage = {
                        if (canTurnForward) {
                            lensVisible = false
                            pageTurnScope.launch { pageTurnState.next() }
                        }
                    },
                    onAddBookmark = onAddPageBookmark,
                    onShowBookmarks = { pageBookmarksVisible = true; controlsVisible = false },
                    onShowSearch = { pdfSearchVisible = true; pdfSearchQuery = ""; controlsVisible = false },
                    onSetPageDisplayMode = onSetPageDisplayMode,
                    onToggleDirection = onToggleDirection,
                    onSetGuidedMode = onSetGuidedMode,
                    onPreviousRegion = onPreviousRegion,
                    onNextRegion = onNextRegion,
                    onSetSeparateCover = onSetSeparateCover,
                    onSetPanoramasAlone = onSetPanoramasAlone,
                    onSetCanvasBackground = onSetReaderCanvasBackground,
                    onSelectRegion = { enabled ->
                        onSelectRegion(enabled)
                        if (enabled) controlsVisible = false
                    },
                    onZoom = onZoom,
                    onRecognize = onRecognize,
                    onCancelSelection = { onSelectRegion(false); controlsVisible = false },
                    onExitZoom = onExitZoom,
                    onCopyText = { text -> clipboard?.setPrimaryClip(ClipData.newPlainText("Texto reconhecido", text)) },
                )
            }
        }
        if (pageBookmarksVisible) {
            AlertDialog(
                onDismissRequest = { pageBookmarksVisible = false },
                title = { Text("Marcadores") },
                text = {
                    if (state.pageBookmarks.isEmpty()) Text("Nenhum marcador salvo neste documento.")
                    else Column(Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        state.pageBookmarks.sortedBy { it.chapterIndex }.forEach { bookmark ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { pageBookmarksVisible = false; onPageChange(bookmark.chapterIndex) },
                                ) { Text(bookmark.title, maxLines = 1) }
                                TextButton(onClick = { onRemovePageBookmark(bookmark) }) { Text("Remover") }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pageBookmarksVisible = false }) { Text("Fechar") } },
            )
        }
        if (pdfSearchVisible) {
            AlertDialog(
                onDismissRequest = { pdfSearchVisible = false },
                title = { Text("Buscar texto no PDF") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = pdfSearchQuery,
                                onValueChange = { pdfSearchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("Buscar") },
                            )
                            TextButton(
                                onClick = { onSearchPdfText(pdfSearchQuery) },
                                enabled = pdfSearchQuery.isNotBlank() && !state.isSearchingPdfText,
                            ) { Text(if (state.isSearchingPdfText) "Buscando…" else "Buscar") }
                        }
                        state.pdfTextSearchError?.let { Text(it, Modifier.padding(top = 8.dp), color = Color(0xFF706B62)) }
                        if (!state.isSearchingPdfText && state.pdfTextSearchResults.isEmpty() && state.pdfTextSearchError == null && pdfSearchQuery.isNotBlank()) {
                            Text("Nenhum texto encontrado. Se o PDF for digitalizado, a busca por OCR ainda não está disponível.", Modifier.padding(top = 8.dp))
                        }
                        if (state.pdfTextSearchResults.isNotEmpty()) {
                            Column(Modifier.heightIn(max = 280.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                                state.pdfTextSearchResults.forEach { result ->
                                    TextButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { onOpenPdfTextSearchResult(result); pdfSearchVisible = false },
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text("Página ${result.pageIndex + 1}", color = Color(0xFFA95138))
                                            Text(result.snippet, maxLines = 2, color = Color(0xFF292722))
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pdfSearchVisible = false }) { Text("Fechar") } },
            )
        }
    }
}

@Composable
private fun StaticComicPage(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    backgroundColor: Color = ReaderCanvasBackground.default.color,
) {
    Box(modifier.fillMaxSize().background(backgroundColor), contentAlignment = Alignment.Center) {
        if (bitmap != null && !bitmap.isRecycled) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "Página do quadrinho",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun StaticSpreadPage(
    images: ReaderSpreadImages?,
    direction: com.estante.reader.core.guided.ReadingDirection,
    backgroundColor: Color,
) {
    val secondBitmap = images?.secondBitmap
    if (images == null || images.spread.second == null || secondBitmap == null) {
        StaticComicPage(images?.firstBitmap, backgroundColor = backgroundColor)
        return
    }
    val ordered = if (direction == com.estante.reader.core.guided.ReadingDirection.LEFT_TO_RIGHT) {
        listOf(images.firstBitmap, secondBitmap)
    } else {
        listOf(secondBitmap, images.firstBitmap)
    }
    Row(Modifier.fillMaxSize()) {
        ordered.forEachIndexed { index, page ->
            if (index == 1) Spacer(Modifier.width(20.dp))
            StaticComicPage(page, Modifier.weight(1f).fillMaxHeight(), backgroundColor)
        }
    }
}

@Composable
private fun PageCanvas(
    modifier: Modifier = Modifier,
    bitmap: Bitmap,
    selectionMode: Boolean,
    selected: NormalizedRect?,
    regions: List<GuidedRegion>,
    activeRegionIndex: Int,
    guidedMode: Boolean,
    onChooseRegion: (Int) -> Unit,
    onRegionSelected: (NormalizedRect) -> Unit,
    onBackgroundTap: (Offset, IntSize) -> Unit = { _, _ -> },
    onLongPress: () -> Unit = {},
    onDismissLens: () -> Unit = {},
    searchMatches: List<NormalizedRect> = emptyList(),
    pageIndex: Int = -1,
    onZoomStateChange: (Int, Boolean) -> Unit = { _, _ -> },
) {
    var dragStart by remember(bitmap) { mutableStateOf<Offset?>(null) }
    var dragPreview by remember(bitmap) { mutableStateOf<NormalizedRect?>(null) }
    var zoomScale by remember(bitmap) { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var zoomOffset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    val bitmapImage: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val activeBounds = regions.getOrNull(activeRegionIndex)?.bounds ?: selected
    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val previousScale = zoomScale
        val newScale = (zoomScale * zoomChange).coerceIn(1f, 4f)
        zoomScale = newScale
        if (newScale <= 1f) {
            zoomOffset = Offset.Zero
        } else {
            val limitX = viewportSize.width * (newScale - 1f) / 2f
            val limitY = viewportSize.height * (newScale - 1f) / 2f
            val factor = newScale / previousScale
            val pivot = Offset(centroid.x - viewportSize.width / 2f, centroid.y - viewportSize.height / 2f)
            zoomOffset = Offset(
                (pivot.x + (zoomOffset.x - pivot.x) * factor + panChange.x).coerceIn(-limitX, limitX),
                (pivot.y + (zoomOffset.y - pivot.y) * factor + panChange.y).coerceIn(-limitY, limitY),
            )
        }
    }
    LaunchedEffect(pageIndex, zoomScale > 1f) {
        if (pageIndex >= 0) onZoomStateChange(pageIndex, zoomScale > 1f)
    }
    DisposableEffect(pageIndex) {
        onDispose { if (pageIndex >= 0) onZoomStateChange(pageIndex, false) }
    }
    BoxWithConstraints(
        modifier = modifier
            .pointerInput(bitmap, regions, selectionMode) {
                if (selectionMode) return@pointerInput
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { point ->
                        val normalized = toNormalized(point, size, bitmap.width, bitmap.height)
                        if (normalized == null) {
                            onBackgroundTap(point, IntSize(size.width, size.height))
                            return@detectTapGestures
                        }
                        val hit = regions.indices.reversed().firstOrNull { index ->
                            val rect = regions[index].bounds
                            normalized.first in rect.left..rect.right && normalized.second in rect.top..rect.bottom
                        }
                        if (hit != null) onChooseRegion(hit)
                        else onBackgroundTap(point, IntSize(size.width, size.height))
                    },
                )
            }
            .pointerInput(bitmap, selectionMode) {
                if (!selectionMode) return@pointerInput
                var start: Pair<Float, Float>? = null
                detectDragGestures(
                    onDragStart = { position ->
                        start = toNormalized(position, size, bitmap.width, bitmap.height)
                        dragStart = position
                        dragPreview = null
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val from = start ?: return@detectDragGestures
                        val to = toNormalized(change.position, size, bitmap.width, bitmap.height) ?: return@detectDragGestures
                        dragPreview = NormalizedRect.clipped(
                            min(from.first, to.first),
                            min(from.second, to.second),
                            max(from.first, to.first),
                            max(from.second, to.second),
                        )
                    },
                    onDragEnd = {
                        dragPreview?.let(onRegionSelected)
                        dragStart = null
                        dragPreview = null
                    },
                    onDragCancel = {
                        dragStart = null
                        dragPreview = null
                    },
                )
            }
            .onSizeChanged { viewportSize = IntSize(it.width, it.height) }
            .pageCanvasTransformable(
                state = transformState,
                currentScale = { zoomScale },
                enabled = !selectionMode,
            )
            .clipToBounds()
            .graphicsLayer {
                scaleX = zoomScale
                scaleY = zoomScale
                translationX = zoomOffset.x
                translationY = zoomOffset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmapImage,
            contentDescription = "Página do quadrinho",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(Modifier.fillMaxSize()) {
            val fit = fittedImageRect(size, bitmap.width, bitmap.height)
            if (guidedMode) {
                regions.getOrNull(activeRegionIndex)?.bounds?.let { box ->
                    val left = fit.left + box.left * fit.width
                    val top = fit.top + box.top * fit.height
                    val rectSize = Size(box.width * fit.width, box.height * fit.height)
                    drawRect(
                        color = Color(0xFFFFB48F),
                        topLeft = Offset(left, top),
                        size = rectSize,
                        style = Stroke(width = 2.5.dp.toPx()),
                    )
                }
            }
            searchMatches.forEach { match ->
                drawRect(
                    color = Color(0x66F5CF59),
                    topLeft = Offset(fit.left + match.left * fit.width, fit.top + match.top * fit.height),
                    size = Size(match.width * fit.width, match.height * fit.height),
                )
            }
            val rect = dragPreview ?: if (guidedMode) null else selected
            if (rect != null && selectionMode) {
                val left = fit.left + rect.left * fit.width
                val top = fit.top + rect.top * fit.height
                val width = rect.width * fit.width
                val height = rect.height * fit.height
                drawRect(
                    color = Color(0x50F5B18D),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                )
                drawRect(
                    color = Color(0xFFFFB48F),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        if (selectionMode && dragStart == null && selected == null) {
            Text(
                text = "Desenhe uma caixa sobre o balão",
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(10.dp),
                fontSize = 13.sp,
            )
        }
        if (guidedMode && activeBounds != null) {
            BubbleLensOverlay(
                modifier = Modifier.align(Alignment.TopStart),
                bitmap = bitmapImage,
                bounds = activeBounds,
                viewport = IntSize(constraints.maxWidth, constraints.maxHeight),
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                onDismiss = onDismissLens,
            )
        }
    }
}

@Composable
private fun BubbleLensOverlay(
    modifier: Modifier,
    bitmap: ImageBitmap,
    bounds: NormalizedRect,
    viewport: IntSize,
    imageWidth: Int,
    imageHeight: Int,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val fit = fittedImageRect(Size(viewport.width.toFloat(), viewport.height.toFloat()), imageWidth, imageHeight)
    val bubbleCenterX = fit.left + bounds.centerX * fit.width
    val bubbleTop = fit.top + bounds.top * fit.height
    val maxLensWidthPx = viewport.width * 0.84f
    val maxLensHeightPx = viewport.height * 0.48f
    val minLensWidthPx = minOf(with(density) { 180.dp.toPx() }, maxLensWidthPx)
    val minLensHeightPx = minOf(with(density) { 104.dp.toPx() }, maxLensHeightPx)
    val bubbleWidthPx = bounds.width * fit.width
    val bubbleHeightPx = bounds.height * fit.height
    val lensWidthPx = (bubbleWidthPx * 2f).coerceIn(minLensWidthPx, maxLensWidthPx)
    val lensHeightPx = (bubbleHeightPx * 2f).coerceIn(minLensHeightPx, maxLensHeightPx)
    val gapPx = with(density) { 8.dp.toPx() }
    val leftPx = (bubbleCenterX - lensWidthPx / 2f)
        .coerceIn(0f, (viewport.width - lensWidthPx).coerceAtLeast(0f))
    val topPx = (bubbleTop - lensHeightPx - gapPx)
        .coerceIn(0f, (viewport.height - lensHeightPx).coerceAtLeast(0f))
    val marginRect = bounds.expanded(0.04f)
    val sourceLeft = (marginRect.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
    val sourceTop = (marginRect.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
    val sourceRight = (marginRect.right * imageWidth).toInt().coerceIn(sourceLeft + 1, imageWidth)
    val sourceBottom = (marginRect.bottom * imageHeight).toInt().coerceIn(sourceTop + 1, imageHeight)

    Box(
        modifier = modifier
            .pointerInput(bounds, onDismiss) {
                detectTapGestures { onDismiss() }
            }
            .offset(with(density) { leftPx.toDp() }, with(density) { topPx.toDp() })
            .size(with(density) { lensWidthPx.toDp() }, with(density) { lensHeightPx.toDp() })
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xEE181816))
            .border(1.dp, Color(0xFFFFB48F), RoundedCornerShape(18.dp))
            .semantics { contentDescription = "Lupa no balão atual" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val sourceWidth = sourceRight - sourceLeft
            val sourceHeight = sourceBottom - sourceTop
            val scale = min(size.width / sourceWidth, size.height / sourceHeight)
            val drawWidth = sourceWidth * scale
            val drawHeight = sourceHeight * scale
            drawImage(
                image = bitmap,
                srcOffset = IntOffset(sourceLeft, sourceTop),
                srcSize = IntSize(sourceWidth, sourceHeight),
                dstOffset = IntOffset(((size.width - drawWidth) / 2f).roundToInt(), ((size.height - drawHeight) / 2f).roundToInt()),
                dstSize = IntSize(drawWidth.roundToInt().coerceAtLeast(1), drawHeight.roundToInt().coerceAtLeast(1)),
            )
        }
    }
}

private data class FittedRect(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fittedImageRect(viewport: Size, imageWidth: Int, imageHeight: Int): FittedRect {
    val scale = min(viewport.width / imageWidth, viewport.height / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FittedRect((viewport.width - width) / 2f, (viewport.height - height) / 2f, width, height)
}

private fun toNormalized(
    point: Offset,
    viewport: IntSize,
    imageWidth: Int,
    imageHeight: Int,
): Pair<Float, Float>? {
    val fit = fittedImageRect(Size(viewport.width.toFloat(), viewport.height.toFloat()), imageWidth, imageHeight)
    if (point.x !in fit.left..(fit.left + fit.width) || point.y !in fit.top..(fit.top + fit.height)) return null
    val x = ((point.x - fit.left) / fit.width).coerceIn(0f, 1f)
    val y = ((point.y - fit.top) / fit.height).coerceIn(0f, 1f)
    return x to y
}

@Composable
private fun ErrorMessage(message: String) {
    Text(
        text = message,
        color = Terracotta,
        modifier = Modifier.padding(top = 14.dp),
        textAlign = TextAlign.Center,
        fontSize = 13.sp,
    )
}
