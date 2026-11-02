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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.estante.reader.core.guided.GuidedRegion
import com.estante.reader.core.guided.NormalizedRect
import com.estante.reader.core.reader.PageDisplayMode
import com.estante.reader.core.reader.ReaderSpread
import com.estante.reader.core.reader.ReaderSpreadBuilder
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.rememberPageCurlConfig
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
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::openDocument) }

    if (state.title != null && state.pageCount > 0) {
        ComicReaderScreen(
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
            onToggleGuided = { viewModel.setGuidedModeEnabled(!state.guidedModeEnabled) },
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
            onLoadSpreadPage = viewModel::loadSpreadPage,
            onLoadNeighborSpreads = viewModel::loadNeighborSpreads,
        )
    } else {
        LibraryScreen(
            state = state,
            onImport = { documentPicker.launch(arrayOf("*/*")) },
            comics = comics,
            onOpenComic = viewModel::openComic,
            onDeleteComic = viewModel::removeComic,
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
) {
    var comicToDelete by remember { mutableStateOf<LibraryComicUi?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.estante_logo),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "ESTANTE",
                            color = Olive,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            text = "Sua próxima leitura.",
                            color = Ink,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Button(
                    onClick = onImport,
                    enabled = !state.isLoading && !state.isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = Terracotta),
                ) {
                    Text(if (state.isImporting) "Importando…" else "Adicionar")
                }
            }

            state.error?.let { ErrorMessage(it) }
            if (state.isImporting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Terracotta, strokeWidth = 2.dp)
                    Text("  Preparando quadrinho…", color = Muted, fontSize = 13.sp)
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = Terracotta)
            } else if (comics.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sua estante está vazia", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Adicione quadrinhos em PDF, CBZ ou CBR para começar.", color = Muted, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = Terracotta)) { Text("Adicionar quadrinho") }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 136.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(comics, key = { it.id }) { comic ->
                        Column(
                            Modifier.clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = { onOpenComic(comic.id) },
                                    onLongClick = { comicToDelete = comic },
                                )
                                .padding(6.dp),
                        ) {
                            Box(Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE8DFD0)), contentAlignment = Alignment.Center) {
                                LibraryCover(comic.coverPath, comic.title)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(comic.title, color = Ink, maxLines = 2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (comic.pageCount > 0) Text("${(comic.pageIndex + 1).coerceAtMost(comic.pageCount)} / ${comic.pageCount}", color = Muted, fontSize = 12.sp)
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
private fun LibraryCover(path: String?, title: String) {
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
        Image(image!!, contentDescription = "Capa de $title", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    } else {
        Text("▤", color = Terracotta, fontSize = 40.sp)
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
    onToggleGuided: () -> Unit = {},
    onPreviousRegion: () -> Unit = {},
    onNextRegion: () -> Unit = {},
    onChooseRegion: (Int) -> Unit = {},
    onToggleDirection: () -> Unit = {},
    onSetPageDisplayMode: (PageDisplayMode) -> Unit = {},
    onSetSeparateCover: (Boolean) -> Unit = {},
    onSetPanoramasAlone: (Boolean) -> Unit = {},
    onLoadSpreadPage: (Int?) -> Unit = {},
    onLoadNeighborSpreads: (Int?, Int?) -> Unit = { _, _ -> },
) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val context = LocalContext.current
    val view = LocalView.current
    val window = (context as? Activity)?.window
    var controlsVisible by remember(state.title) { mutableStateOf(false) }
    var lensVisible by remember(state.title, state.pageIndex) { mutableStateOf(false) }
    var lensPageIndex by remember(state.title, state.pageIndex) { mutableIntStateOf(state.pageIndex) }
    var companionActiveRegion by remember(state.title, state.pageIndex) { mutableIntStateOf(0) }
    val pageTurnScope = rememberCoroutineScope()
    BackHandler {
        when {
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
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF181816)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val requestedDoublePage = when (state.pageDisplayMode) {
                PageDisplayMode.SINGLE_PAGE -> false
                PageDisplayMode.DOUBLE_PAGE -> true
                PageDisplayMode.AUTOMATIC -> maxWidth >= 600.dp
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
            val isLeftToRight = state.readingDirection == com.estante.reader.core.guided.ReadingDirection.LEFT_TO_RIGHT
            val leftHalf = Rect(0f, 0f, 0.5f, 1f)
            val rightHalf = Rect(0.5f, 0f, 1f, 1f)
            val curlDragInteraction = PageCurlConfig.StartEndDragInteraction(
                pointerBehavior = PageCurlConfig.DragInteraction.PointerBehavior.PageEdge,
                forward = PageCurlConfig.StartEndDragInteraction.Config(
                    start = if (isLeftToRight) rightHalf else leftHalf,
                    end = if (isLeftToRight) leftHalf else rightHalf,
                ),
                backward = PageCurlConfig.StartEndDragInteraction.Config(
                    start = if (isLeftToRight) leftHalf else rightHalf,
                    end = if (isLeftToRight) rightHalf else leftHalf,
                ),
            )
            val pageCurlConfig = rememberPageCurlConfig(
                backPageColor = Paper,
                backPageContentAlpha = 0.08f,
                shadowColor = Color.Black,
                shadowAlpha = 0.28f,
                shadowRadius = 18.dp,
                shadowOffset = DpOffset((-6).dp, 0.dp),
                dragInteraction = curlDragInteraction,
                tapForwardEnabled = false,
                tapBackwardEnabled = false,
                tapCustomEnabled = false,
            )
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
                !lensVisible && !controlsVisible
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
                    controlsVisible = false
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
                    if (point.x in size.width * 0.30f..size.width * 0.70f) {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) lensVisible = false
                    } else if (lensVisible || controlsVisible) {
                        lensVisible = false
                        controlsVisible = false
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
                            StaticSpreadPage(state.previousSpreadImages, state.readingDirection)
                        } else {
                            StaticComicPage(state.previousPageBitmap)
                        }
                        2 -> if (doublePage) {
                            StaticSpreadPage(state.nextSpreadImages, state.readingDirection)
                        } else {
                            StaticComicPage(state.nextPageBitmap)
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
                                        onBackgroundTap = onTapBackground,
                                        onLongPress = { controlsVisible = true; lensVisible = false },
                                        onDismissLens = { lensVisible = false },
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
            if (controlsVisible) Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC181816))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, shape = RoundedCornerShape(14.dp)) {
                    Text("Estante")
                }
                Text(
                    text = state.title.orEmpty(),
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    maxLines = 1,
                    fontSize = 14.sp,
                )
                Text(
                    text = if (spreadMode) {
                        "${displaySpread.first + 1}–${displaySpread.second + 1} / ${state.pageCount}"
                    } else "${state.pageIndex + 1} / ${state.pageCount}",
                    color = Color(0xFFD8D3CA),
                    fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = onToggleDirection,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.padding(start = 6.dp),
                ) {
                    Text(if (state.readingDirection == com.estante.reader.core.guided.ReadingDirection.LEFT_TO_RIGHT) "LTR" else "RTL", fontSize = 11.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xCC181816)).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Páginas", color = Color(0xFFD8D3CA), fontSize = 13.sp)
                OutlinedButton(
                    onClick = {
                        val nextMode = when (state.pageDisplayMode) {
                            PageDisplayMode.SINGLE_PAGE -> PageDisplayMode.DOUBLE_PAGE
                            PageDisplayMode.DOUBLE_PAGE -> PageDisplayMode.AUTOMATIC
                            PageDisplayMode.AUTOMATIC -> PageDisplayMode.SINGLE_PAGE
                        }
                        onSetPageDisplayMode(nextMode)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        when (state.pageDisplayMode) {
                            PageDisplayMode.SINGLE_PAGE -> "Uma"
                            PageDisplayMode.DOUBLE_PAGE -> "Duas"
                            PageDisplayMode.AUTOMATIC -> "Automático"
                        },
                        fontSize = 12.sp,
                    )
                }
                if (state.pageDisplayMode != PageDisplayMode.SINGLE_PAGE) {
                    Text("Capa sozinha", color = Color(0xFFD8D3CA), fontSize = 12.sp)
                    Switch(checked = state.separateCover, onCheckedChange = onSetSeparateCover)
                }
            }
            if (state.pageDisplayMode != PageDisplayMode.SINGLE_PAGE) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xCC181816)).padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Páginas panorâmicas sozinhas", color = Color(0xFFD8D3CA), fontSize = 12.sp)
                    Switch(checked = state.panoramasAlone, onCheckedChange = onSetPanoramasAlone)
                }
            }

            Spacer(Modifier.weight(1f))

            if (state.isLoading && bitmap != null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp), color = Color(0xFFE4AA8D))
            }
            if (state.isPreparingZoom) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = Color(0xFFE4AA8D))

            state.error?.let {
                Text(
                    it,
                    color = Color(0xFFFFB4A4),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 13.sp,
                )
            }
            state.analysisError?.let {
                Text(
                    it,
                    color = Color(0xFFFFB4A4),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 13.sp,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        if (canTurnBackward) {
                            lensVisible = false
                            pageTurnScope.launch { pageTurnState.prev() }
                        }
                    },
                    enabled = !state.isLoading && pageTurnState.progress == 0f && canTurnBackward,
                ) { Text("‹") }

                Text(
                    text = when {
                        state.selectionMode -> "Arraste sobre um balão"
                        isGuidedMode && regions.isNotEmpty() -> "Balão ${activeRegionIndex.coerceAtLeast(0) + 1} de ${regions.size}"
                        isAnalysisRunning -> "Preparando balões…"
                        spreadMode -> "Páginas ${displaySpread.first + 1}–${displaySpread.second + 1}"
                        else -> "Página ${state.pageIndex + 1}"
                    },
                    color = Color(0xFFE4DED3),
                    fontSize = 13.sp,
                )

                if (state.selectionMode) {
                    Button(
                        onClick = onRecognize,
                        enabled = state.selection != null && !state.isRecognizing,
                        colors = ButtonDefaults.buttonColors(containerColor = Terracotta),
                    ) {
                        if (state.isRecognizing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Ler balão")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            if (canTurnForward) {
                                lensVisible = false
                                pageTurnScope.launch { pageTurnState.next() }
                            }
                        },
                        enabled = !state.isLoading && pageTurnState.progress == 0f && canTurnForward,
                    ) { Text("›") }
                }
            }

            if (state.recognizedText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2B27))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.recognizedText,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                    )
                    if (state.guidedBitmap != null) {
                        OutlinedButton(onClick = onExitZoom) { Text("Voltar") }
                    }
                    Button(
                        onClick = { clipboard?.setPrimaryClip(ClipData.newPlainText("Texto reconhecido", state.recognizedText)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Terracotta),
                    ) { Text("Copiar") }
                }
            } else if (state.selectionMode && state.selection != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2B27))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { onSelectRegion(false) }) { Text("Cancelar") }
                    Button(
                        onClick = onZoom,
                        enabled = !state.isPreparingZoom,
                        colors = ButtonDefaults.buttonColors(containerColor = Olive),
                    ) { Text("Ampliar balão") }
                    Button(
                        onClick = onRecognize,
                        enabled = !state.isRecognizing,
                        colors = ButtonDefaults.buttonColors(containerColor = Terracotta),
                    ) { Text("Ler texto") }
                }
            } else if (isGuidedMode && regions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2B27))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onPreviousRegion,
                        enabled = activeRegionIndex > 0 || state.pageIndex > 0,
                    ) { Text("‹") }
                    Text(
                        text = "${activeRegionIndex.coerceAtLeast(0) + 1} / ${regions.size}",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    Button(
                        onClick = onNextRegion,
                        colors = ButtonDefaults.buttonColors(containerColor = Terracotta),
                    ) { Text("Próximo balão ›") }
                    OutlinedButton(onClick = onRecognize, enabled = !state.isRecognizing) { Text("OCR") }
                    OutlinedButton(onClick = onToggleGuided) { Text("Página") }
                }
            } else if (isAnalysisRunning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2B27))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (totalAnalysisPageCount > 0) "Preparando balões · $analyzedPageCount/$totalAnalysisPageCount" else "Preparando balões…",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                    OutlinedButton(onClick = { onSelectRegion(true) }, enabled = state.bitmap != null) {
                        Text("Selecionar manualmente")
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2B27))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(text = "Lupa de balões", color = Color.White, fontWeight = FontWeight.SemiBold)
                        if (regions.isEmpty() && analyzedPageCount > 0) {
                            Text("Nenhum balão nesta página", color = Color(0xFFBDB8AD), fontSize = 12.sp)
                        }
                    }
                    if (regions.isNotEmpty()) {
                        OutlinedButton(onClick = onToggleGuided) { Text("Ler balões") }
                    }
                    OutlinedButton(
                        onClick = { onSelectRegion(!state.selectionMode) },
                        enabled = state.bitmap != null && !state.isLoading,
                    ) {
                        Text(if (state.selectionMode) "Cancelar seleção" else "Selecionar balão")
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun StaticComicPage(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color(0xFF181816)), contentAlignment = Alignment.Center) {
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
private fun StaticSpreadPage(images: ReaderSpreadImages?, direction: com.estante.reader.core.guided.ReadingDirection) {
    val secondBitmap = images?.secondBitmap
    if (images == null || images.spread.second == null || secondBitmap == null) {
        StaticComicPage(images?.firstBitmap)
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
            StaticComicPage(page, Modifier.weight(1f).fillMaxHeight())
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
) {
    var dragStart by remember(bitmap) { mutableStateOf<Offset?>(null) }
    var dragPreview by remember(bitmap) { mutableStateOf<NormalizedRect?>(null) }
    val bitmapImage: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val activeBounds = regions.getOrNull(activeRegionIndex)?.bounds ?: selected
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
