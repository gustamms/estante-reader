package com.estante.reader

import android.app.Activity
import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.estante.reader.core.data.BookHighlightEntity
import com.estante.reader.core.publication.PublicationBookmark
import com.estante.reader.core.publication.PublicationChapter
import com.estante.reader.core.publication.PublicationCapabilities
import com.estante.reader.core.publication.PublicationSearchResult
import com.estante.reader.core.publication.BookColumnMode
import com.estante.reader.core.publication.PublicationResourceMimeType
import com.estante.reader.core.publication.publicationGlobalProgress
import com.estante.reader.core.publication.publicationLocationAtProgress
import java.io.File
import java.net.URLDecoder
import com.estante.reader.core.guided.ReadingDirection
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun BookReaderScreen(
    title: String,
    author: String?,
    bookId: String,
    rootPath: String,
    chapters: List<PublicationChapter>,
    chapterIndex: Int,
    progression: Float,
    fontSizeSp: Float,
    lineSpacing: Float,
    marginsDp: Float,
    columnMode: BookColumnMode,
    background: ReaderCanvasBackground,
    readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    capabilities: PublicationCapabilities,
    bookmarks: List<PublicationBookmark>,
    highlights: List<BookHighlightEntity>,
    highlightColorId: String,
    pendingHighlight: BookHighlightEntity?,
    searchResults: List<PublicationSearchResult>,
    isSearching: Boolean,
    onClose: () -> Unit,
    onProgress: (Int, Float) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onMarginsChange: (Float) -> Unit,
    onColumnsChange: (BookColumnMode) -> Unit,
    onBackgroundChange: (ReaderCanvasBackground) -> Unit,
    onAddBookmark: () -> Unit,
    onTextSelection: (String, String, String, Int, Int, String, String, (BookHighlightSaveResult) -> Unit) -> Unit,
    onHighlightColorChange: (String) -> Unit,
    onHighlightItemColorChange: (String, String) -> Unit,
    onOpenHighlight: (String) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onRestoreHighlight: (BookHighlightEntity) -> Unit,
    onPendingHighlightConsumed: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenSearchResult: (PublicationSearchResult) -> Unit,
    onToggleDirection: () -> Unit = {},
) {
    var controlsVisible by remember(bookId) { mutableStateOf(false) }
    var showContents by remember(bookId) { mutableStateOf(false) }
    var showBookmarks by remember(bookId) { mutableStateOf(false) }
    var showSearch by remember(bookId) { mutableStateOf(false) }
    var showSearchResults by remember(bookId) { mutableStateOf(false) }
    var showHighlightsPanel by remember(bookId) { mutableStateOf(false) }
    var searchQuery by remember(bookId) { mutableStateOf("") }
    val chapter = chapters.getOrNull(chapterIndex)
    val chapterCount = chapters.size.coerceAtLeast(1)
    val totalProgress = publicationGlobalProgress(chapters, chapterIndex, progression)
    var sliderProgress by remember(bookId) { androidx.compose.runtime.mutableFloatStateOf(totalProgress) }
    var sliderDragging by remember(bookId) { mutableStateOf(false) }
    LaunchedEffect(chapterIndex, progression) {
        if (!sliderDragging) sliderProgress = totalProgress
    }
    var visiblePage by remember(bookId, chapter?.href) { mutableStateOf(1 to 1) }
    var chapterLoadError by remember(bookId, chapter?.href) { mutableStateOf<String?>(null) }
    val chapterUrl = remember(bookId, chapter?.href) {
        "https://reader.estante.local/book/$bookId/${Uri.encode(chapter?.href.orEmpty(), "/-_.~")}"
    }
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestProgression by rememberUpdatedState(progression)
    val goToChapter: (Int) -> Unit = { index ->
        val value = index.coerceIn(0, chapterCount - 1)
        latestOnProgress(value, 0f)
    }
    val latestOnGoToChapter by rememberUpdatedState(goToChapter)
    val latestChapterIndex by rememberUpdatedState(chapterIndex)
    val latestReadingDirection by rememberUpdatedState(readingDirection)
    val setControls: (Boolean) -> Unit = { visible -> controlsVisible = visible }
    val latestToggleControls by rememberUpdatedState(setControls)
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val latestHighlights by rememberUpdatedState(highlights)
    val latestPendingHighlight by rememberUpdatedState(pendingHighlight)
    val latestOnTextSelection by rememberUpdatedState(onTextSelection)
    val latestOnRemoveHighlight by rememberUpdatedState(onRemoveHighlight)
    val latestOnHighlightItemColorChange by rememberUpdatedState(onHighlightItemColorChange)
    val latestOnRestoreHighlight by rememberUpdatedState(onRestoreHighlight)
    val latestOnPendingConsumed by rememberUpdatedState(onPendingHighlightConsumed)
    val webView = remember(bookId) { mutableStateOf<WebView?>(null) }
    val pageTurn = remember(bookId) { BookPageTurnController() }
    val snackbarHostState = remember(bookId) { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val localView = LocalView.current
    val window = (context as? Activity)?.window

    BackHandler {
        when {
            showContents -> showContents = false
            showBookmarks -> showBookmarks = false
            showSearchResults -> showSearchResults = false
            showSearch -> showSearch = false
            showHighlightsPanel -> showHighlightsPanel = false
            controlsVisible -> controlsVisible = false
            else -> onClose()
        }
    }
    DisposableEffect(window, localView) {
        val insets = window?.let { WindowCompat.getInsetsController(it, localView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(background.color).safeDrawingPadding()) {
    val twoColumns = when (columnMode) {
        BookColumnMode.AUTOMATIC -> maxWidth >= 680.dp
        BookColumnMode.SINGLE -> false
        BookColumnMode.DOUBLE -> true
    }
    val latestStyle by rememberUpdatedState(ReaderWebStyle(fontSizeSp, twoColumns, background, lineSpacing, marginsDp))
    val widePanelOpen = showHighlightsPanel && maxWidth >= 600.dp
    val panelWidth = 352.dp
    AndroidView(
        modifier = Modifier.fillMaxSize().background(background.color)
            .then(if (widePanelOpen) Modifier.padding(end = panelWidth) else Modifier),
        factory = { viewContext ->
                ReaderWebView(viewContext) { result -> coroutineScope.launch {
                    val message = when (result) {
                        BookHighlightSaveResult.SAVED -> "Trecho grifado"
                        BookHighlightSaveResult.ALREADY_EXISTS -> "Este trecho já está grifado"
                        BookHighlightSaveResult.INVALID_SELECTION -> "Não foi possível grifar esse trecho. Selecione o texto novamente."
                        BookHighlightSaveResult.FAILED -> "Não foi possível salvar este grifo. Tente novamente."
                    }
                    snackbarHostState.showSnackbar(message)
                } }.apply {
                    val readerView = this
                setBackgroundColor(background.color.toArgb())
                settings.javaScriptEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(BookProgressBridge(
                    report = { reportedChapter, value -> post {
                        if (reportedChapter == latestChapterIndex && pageTurn.turn == null) {
                            latestOnProgress(reportedChapter, value)
                        }
                    } },
                    turnChapter = { direction -> post {
                        val next = latestChapterIndex + direction
                        if (next in chapters.indices) latestOnProgress(next, if (direction > 0) 0f else 1f)
                    } },
                    pagination = { page, total -> post { visiblePage = page to total } },
                    requestTurn = { direction -> post {
                        pageTurn.request(this, direction, latestChapterIndex + direction in chapters.indices)
                    } },
                    ready = {},
                    turnInProgress = { pageTurn.turn != null },
                ), "EstanteProgress")
                addJavascriptInterface(BookSelectionBridge(
                    reportSelection = { requestId, href, text, start, end, prefix, suffix ->
                        post {
                            latestOnTextSelection(bookId, href, text, start, end, prefix, suffix) { result ->
                                readerView.post { readerView.completeHighlightRequest(requestId, result) }
                            }
                        }
                    },
                    reportVisible = { id -> post { latestOnPendingConsumed(id) } },
                    removeHighlight = { id -> post {
                        val removed = latestHighlights.firstOrNull { it.id == id } ?: return@post
                        latestOnRemoveHighlight(id)
                        coroutineScope.launch {
                            if (snackbarHostState.showSnackbar("Grifo removido", "Desfazer") == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                latestOnRestoreHighlight(removed)
                            }
                        }
                    } },
                    changeHighlightColor = { id, color -> post { latestOnHighlightItemColorChange(id, color) } },
                    copyText = { selected -> post {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Trecho do livro", selected))
                    } },
                    selectionHasHighlight = { hasHighlight -> post { readerView.setSelectionHasHighlight(hasHighlight) } },
                ), "EstanteHighlights")
                webViewClient = BookWebViewClient(
                    bookId = bookId,
                    root = File(rootPath),
                    chapters = chapters,
                    currentChapter = { latestChapterIndex },
                    progression = { latestProgression },
                    onNavigate = { target -> latestOnGoToChapter(target) },
                    style = { latestStyle },
                    highlights = { latestHighlights },
                    pendingHighlight = { latestPendingHighlight },
                    onPendingHighlightConsumed = { id -> latestOnPendingConsumed(id) },
                    onChapterReady = { view -> pageTurn.chapterReady(view) },
                    onChapterLoadStarted = { chapterLoadError = null },
                    onChapterLoadError = { message -> pageTurn.cancel(); chapterLoadError = message },
                )
                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) pageTurn.cancel()
                }
                val dragTouch = BookDragTouchHandler(
                    pageTurn,
                    canChangeChapter = { direction -> latestChapterIndex + direction in chapters.indices },
                    chapterRestorer = {
                        val sourceChapter = latestChapterIndex
                        { value: Float -> latestOnProgress(sourceChapter, value) }
                    },
                    readingDirection = { latestReadingDirection },
                )
                var downX = 0f
                var downY = 0f
                var downTime = 0L
                setOnTouchListener { _, event ->
                    if (dragTouch.onTouch(this, event, !latestControlsVisible)) return@setOnTouchListener true
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            downTime = event.eventTime
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            val moved = kotlin.math.abs(event.x - downX) > 12 || kotlin.math.abs(event.y - downY) > 12
                            val quickTap = event.eventTime - downTime < 280
                            val edgeDirection = ReaderInteraction.edgeTurnDirection(event.x, width.toFloat(), latestReadingDirection)
                            if (quickTap && !moved && !latestControlsVisible && edgeDirection != null) {
                                cancelBookPointer(event)
                                evaluateJavascript("if(window.getSelection().isCollapsed && window.estanteTurnPage) window.estanteTurnPage($edgeDirection);", null)
                                return@setOnTouchListener true
                            }
                            if (quickTap && !moved && ReaderInteraction.isCenterTap(event.x, event.y, width.toFloat(), height.toFloat())) {
                                cancelBookPointer(event)
                                latestToggleControls(!latestControlsVisible)
                                return@setOnTouchListener true
                            }
                        }
                    }
                    false
                }
                tag = chapterUrl
                loadUrl(chapterUrl)
                webView.value = this
            }
        },
        update = { view ->
            view.setBackgroundColor(background.color.toArgb())
            if (view.tag != chapterUrl) {
                view.tag = chapterUrl
                view.loadUrl(chapterUrl)
            }
        },
    )

    BookPageTurnOverlay(
        pageTurn, background,
        Modifier.then(if (widePanelOpen) Modifier.padding(end = panelWidth) else Modifier),
        readingDirection,
    )

    chapterLoadError?.let { message ->
        Surface(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            color = Color(0xFFF6F2EA),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Não foi possível abrir este capítulo", color = Color(0xFF292722), fontSize = 18.sp)
                Text(message, color = Color(0xFF625B50), fontSize = 14.sp)
                Button(
                    onClick = {
                        chapterLoadError = null
                        webView.value?.reload()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA95138)),
                ) { Text("Tentar novamente") }
            }
        }
    }

    val currentWebView = webView.value
    DisposableEffect(currentWebView) {
        onDispose {
            pageTurn.cancel(restore = false)
            currentWebView?.apply {
                stopLoading()
                removeJavascriptInterface("EstanteProgress")
                removeJavascriptInterface("EstanteHighlights")
                destroy()
            }
        }
    }

    LaunchedEffect(fontSizeSp, lineSpacing, marginsDp, twoColumns, background) {
        delay(250)
        pageTurn.cancel()
        webView.value?.applyReaderStyle(
            ReaderWebStyle(fontSizeSp, twoColumns, background, lineSpacing, marginsDp),
            latestChapterIndex,
            latestHighlights,
            latestPendingHighlight,
        ) { id -> latestOnPendingConsumed(id) }
    }

    LaunchedEffect(highlights, pendingHighlight, chapterIndex) {
        delay(180)
        webView.value?.let { view ->
            if (view.tag == chapterUrl) {
                view.applyReaderStyle(latestStyle, chapterIndex, highlights, pendingHighlight, onPendingHighlightConsumed)
            }
        }
    }

    if (controlsVisible) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).clickable { controlsVisible = false })
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            color = Color(0xFF292722),
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Estante") }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(title, color = Color.White, fontSize = 15.sp, maxLines = 1)
                    Text(listOfNotNull(author?.takeIf(String::isNotBlank), chapter?.title).joinToString(" · "), color = Color(0xFFD7CFC0), fontSize = 12.sp, maxLines = 1)
                }
                TextButton(onClick = { controlsVisible = false }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Fechar") }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = Color(0xFFF6F2EA),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 16.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = minOf(380.dp, maxHeight * 0.60f)).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { webView.value?.evaluateJavascript("window.estanteTurnPage?.(-1)", null) },
                        enabled = chapterIndex > 0 || visiblePage.first > 1,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFA95138)),
                    ) { Text("Anterior") }
                    Text(
                        text = "Página ${visiblePage.first} de ${visiblePage.second} · Capítulo ${chapterIndex + 1} de $chapterCount",
                        color = Color(0xFF292722), modifier = Modifier.weight(1f), fontSize = 13.sp,
                    )
                    TextButton(
                        onClick = { webView.value?.evaluateJavascript("window.estanteTurnPage?.(1)", null) },
                        enabled = chapterIndex < chapterCount - 1 || visiblePage.first < visiblePage.second,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFA95138)),
                    ) { Text("Próximo") }
                }
                Slider(
                    value = sliderProgress,
                    onValueChange = { value ->
                        sliderDragging = true
                        sliderProgress = value
                    },
                    onValueChangeFinished = {
                        val value = sliderProgress.coerceIn(0f, 1f)
                        val (newIndex, newProgress) = publicationLocationAtProgress(chapters, value)
                        if (newIndex == chapterIndex) {
                            webView.value?.evaluateJavascript(
                                "window.estanteRestoreProgress?.($newProgress);",
                                null,
                            )
                        }
                        latestOnProgress(newIndex, newProgress)
                        sliderDragging = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(color = Color(0xFFE4DED2))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (capabilities.tableOfContents) Button(onClick = { showContents = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA95138))) {
                        Text("Sumário")
                    }
                    if (capabilities.textSearch) TextButton(onClick = { showSearch = !showSearch; searchQuery = "" }) { Text("Buscar") }
                    if (capabilities.bookmarks) {
                        TextButton(onClick = { onAddBookmark(); showBookmarks = true }) { Text("Marcar") }
                        TextButton(onClick = { showBookmarks = true }) { Text("Marcadores ${bookmarks.size}") }
                    }
                    if (capabilities.highlights) TextButton(onClick = { showHighlightsPanel = true; controlsVisible = false }) { Text("Grifos ${highlights.size}") }
                }
                if (showSearch) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Buscar no livro") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedIndicatorColor = Color(0xFFA95138),
                                unfocusedIndicatorColor = Color(0xFFE4DED2),
                            ),
                        )
                        TextButton(
                            onClick = { onSearch(searchQuery); showSearchResults = true },
                            enabled = searchQuery.isNotBlank() && !isSearching,
                        ) { Text(if (isSearching) "Buscando" else "Localizar") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (capabilities.readingDirection) DirectionChoice(
                            if (readingDirection == ReadingDirection.LEFT_TO_RIGHT) "Esquerda → direita" else "Direita → esquerda",
                            Modifier.weight(1f), onToggleDirection,
                        )
                    if (capabilities.typography) {
                        Text("Aa", color = Color(0xFF292722), fontSize = 15.sp)
                        TextButton(onClick = { onFontSizeChange(fontSizeSp - 1f) }, enabled = fontSizeSp > 16f) { Text("−") }
                        Text("${fontSizeSp.toInt()} sp", color = Color(0xFF292722), fontSize = 13.sp)
                        TextButton(onClick = { onFontSizeChange(fontSizeSp + 1f) }, enabled = fontSizeSp < 32f) { Text("+") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (capabilities.columns) {
                        listOf(BookColumnMode.AUTOMATIC, BookColumnMode.SINGLE, BookColumnMode.DOUBLE).forEach { option ->
                            TextButton(onClick = { onColumnsChange(option) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                                Text(
                                    when (option) {
                                        BookColumnMode.AUTOMATIC -> "Auto"
                                        BookColumnMode.SINGLE -> "1 coluna"
                                        BookColumnMode.DOUBLE -> "2 colunas"
                                    },
                                    color = if (columnMode == option) Color(0xFFA95138) else Color(0xFF706B62),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
                if (capabilities.typography) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Entrelinha ${"%.1f".format(lineSpacing)}", color = Color(0xFF706B62), fontSize = 12.sp)
                        Slider(value = lineSpacing, onValueChange = onLineSpacingChange, valueRange = 1.3f..2.2f, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Margens ${marginsDp.toInt()} dp", color = Color(0xFF706B62), fontSize = 12.sp)
                        Slider(value = marginsDp, onValueChange = onMarginsChange, valueRange = 12f..48f, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Fundo", color = Color(0xFF706B62), fontSize = 12.sp)
                    ReaderBackgroundChoices(background, onBackgroundChange)
                }
            }
        }
    }

    if (showContents) {
        AlertDialog(
            onDismissRequest = { showContents = false },
            title = { Text("Sumário") },
            text = {
                LazyColumn {
                    itemsIndexed(chapters) { index, item ->
                        TextButton(onClick = { latestOnGoToChapter(index); showContents = false }) {
                            Text("${index + 1}. ${item.title}", color = Color(0xFF292722), maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showContents = false }) { Text("Fechar") } },
        )
    }

    if (showBookmarks) {
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text("Marcadores") },
            text = {
                if (bookmarks.isEmpty()) Text("Nenhum marcador salvo neste livro.")
                else LazyColumn {
                    itemsIndexed(bookmarks.reversed()) { _, marker ->
                        TextButton(onClick = { onProgress(marker.chapterIndex, marker.progression); showBookmarks = false }) {
                            Text(marker.title, color = Color(0xFF292722), maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBookmarks = false }) { Text("Fechar") } },
        )
    }
    if (showSearchResults) {
        AlertDialog(
            onDismissRequest = { showSearchResults = false },
            title = { Text("Resultados da busca") },
            text = {
                if (isSearching) Text("Procurando no livro…")
                else if (searchResults.isEmpty()) Text("Nenhum resultado encontrado.")
                else LazyColumn {
                    itemsIndexed(searchResults) { _, result ->
                        TextButton(onClick = { onOpenSearchResult(result); showSearchResults = false }) {
                            Column {
                                Text(result.title, color = Color(0xFFA95138), maxLines = 1)
                                Text(result.snippet, color = Color(0xFF292722), maxLines = 3)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSearchResults = false }) { Text("Fechar") } },
        )
    }
    if (showHighlightsPanel) {
        if (!widePanelOpen) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)).clickable { showHighlightsPanel = false },
            )
        }
        BookHighlightsPanel(
            modifier = if (widePanelOpen) {
                Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight().padding(vertical = 8.dp, horizontal = 8.dp)
            } else {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.84f).padding(8.dp)
            },
            bookTitle = title,
            chapters = chapters.map(PublicationChapter::title),
            highlights = highlights,
            defaultColorId = highlightColorId,
            onClose = { showHighlightsPanel = false },
            onDefaultColorChange = onHighlightColorChange,
            onHighlightColorChange = onHighlightItemColorChange,
            onOpenHighlight = { id ->
                onOpenHighlight(id)
                if (!widePanelOpen) showHighlightsPanel = false
            },
            onRemoveHighlight = onRemoveHighlight,
            onRestoreHighlight = onRestoreHighlight,
        )
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
    )
    }
}

internal data class ReaderWebStyle(
    val fontSizeSp: Float,
    val twoColumns: Boolean,
    val background: ReaderCanvasBackground,
    val lineSpacing: Float,
    val marginsDp: Float,
)

private class BookProgressBridge(
    private val report: (Int, Float) -> Unit,
    private val turnChapter: (Int) -> Unit,
    private val pagination: (Int, Int) -> Unit,
    private val requestTurn: (Int) -> Unit,
    private val ready: () -> Unit,
    private val turnInProgress: () -> Boolean,
) {
    @JavascriptInterface fun isTurning(): Boolean = turnInProgress()
    @JavascriptInterface fun onTurnRequested(direction: Int) { if (direction == -1 || direction == 1) requestTurn(direction) }
    @JavascriptInterface fun onReady() { ready() }
    @JavascriptInterface fun onChapterBoundary(direction: Int) { if (direction == -1 || direction == 1) turnChapter(direction) }
    @JavascriptInterface fun onPagination(page: Int, total: Int) { if (page in 1..total) pagination(page, total) }

    @JavascriptInterface
    fun onProgress(chapterIndex: Int, progression: Float) {
        report(chapterIndex, progression)
    }
}

private class BookSelectionBridge(
    private val reportSelection: (String, String, String, Int, Int, String, String) -> Unit,
    private val reportVisible: (String) -> Unit,
    private val removeHighlight: (String) -> Unit,
    private val changeHighlightColor: (String, String) -> Unit,
    private val copyText: (String) -> Unit,
    private val selectionHasHighlight: (Boolean) -> Unit,
) {
    @JavascriptInterface
    fun onTextSelection(
        requestId: String,
        chapterHref: String,
        selectedText: String,
        startOffset: Int,
        endOffset: Int,
        prefix: String,
        suffix: String,
    ) {
        if (requestId.length <= 80) {
            reportSelection(requestId, chapterHref.take(2048), selectedText.take(5001), startOffset, endOffset, prefix.takeLast(80), suffix.take(80))
        }
    }

    @JavascriptInterface
    fun onHighlightVisible(id: String) {
        if (id.length <= 64) reportVisible(id)
    }

    @JavascriptInterface fun onRemoveHighlight(id: String) { if (id.length <= 64) removeHighlight(id) }
    @JavascriptInterface fun onChangeHighlightColor(id: String, color: String) {
        if (id.length <= 64 && color in setOf("yellow", "green", "blue", "pink")) changeHighlightColor(id, color)
    }
    @JavascriptInterface fun onCopyText(text: String) { if (text.length <= 5000) copyText(text) }
    @JavascriptInterface fun onSelectionHasHighlight(hasHighlight: Boolean) { selectionHasHighlight(hasHighlight) }
}

private const val SELECTION_ACTION_COPY = 7101
private const val SELECTION_ACTION_HIGHLIGHT = 7102
private const val SELECTION_ACTION_COLORS = 7103
private const val SELECTION_ACTION_REMOVE = 7104
private const val SELECTION_ACTION_COLOR_BASE = 7110

/** Wrap the ActionMode requested by WebView while preserving its selection lifecycle. */
internal class ReaderWebView(
    context: android.content.Context,
    private val onHighlightResult: (BookHighlightSaveResult) -> Unit = {},
) : WebView(context) {
    var selectionActionMode: android.view.ActionMode? = null
        private set
    private var selectedTextHasHighlight = false
    private var pendingHighlightRequestId: String? = null

    fun setSelectionHasHighlight(hasHighlight: Boolean) {
        selectedTextHasHighlight = hasHighlight
        selectionActionMode?.invalidate()
    }

    fun completeHighlightRequest(requestId: String, result: BookHighlightSaveResult) {
        if (pendingHighlightRequestId != requestId) return
        pendingHighlightRequestId = null
        val succeeded = result == BookHighlightSaveResult.SAVED || result == BookHighlightSaveResult.ALREADY_EXISTS
        val jsResult = if (succeeded) "saved" else "failed"
        evaluateJavascript(
            "window.estanteHighlightResult?.(${org.json.JSONObject.quote(requestId)},${org.json.JSONObject.quote(jsResult)});",
        ) {
            val mode = selectionActionMode
            mode?.menu?.findItem(SELECTION_ACTION_HIGHLIGHT)?.isEnabled = true
            if (succeeded) mode?.finish() else mode?.invalidate()
            onHighlightResult(result)
        }
    }

    override fun startActionMode(callback: android.view.ActionMode.Callback): android.view.ActionMode? =
        super.startActionMode(wrapSelectionActions(callback))

    override fun startActionMode(callback: android.view.ActionMode.Callback, type: Int): android.view.ActionMode? =
        super.startActionMode(wrapSelectionActions(callback), type)

    private fun wrapSelectionActions(original: android.view.ActionMode.Callback): android.view.ActionMode.Callback =
        object : android.view.ActionMode.Callback2() {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                if (!original.onCreateActionMode(mode, menu)) return false
                selectionActionMode = mode
                populateMenu(menu)
                return true
            }

            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                original.onPrepareActionMode(mode, menu)
                populateMenu(menu)
                return true
            }

            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                val action = when (item.itemId) {
                    SELECTION_ACTION_COPY -> "window.estanteCopySelection?.()"
                    SELECTION_ACTION_HIGHLIGHT -> {
                        if (pendingHighlightRequestId != null) return true
                        val requestId = java.util.UUID.randomUUID().toString()
                        pendingHighlightRequestId = requestId
                        mode.menu.findItem(SELECTION_ACTION_HIGHLIGHT)?.isEnabled = false
                        evaluateJavascript("window.estanteConfirmHighlight?.(${org.json.JSONObject.quote(requestId)})") { raw ->
                            val status = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                            if (status != "pending" && pendingHighlightRequestId == requestId) {
                                pendingHighlightRequestId = null
                                mode.menu.findItem(SELECTION_ACTION_HIGHLIGHT)?.isEnabled = true
                                mode.invalidate()
                                onHighlightResult(BookHighlightSaveResult.INVALID_SELECTION)
                            }
                        }
                        return true
                    }
                    SELECTION_ACTION_REMOVE -> "window.estanteRemoveSelectedHighlight?.()"
                    in SELECTION_ACTION_COLOR_BASE until SELECTION_ACTION_COLOR_BASE + 4 ->
                        "window.estanteChangeSelectedHighlightColor?.(${org.json.JSONObject.quote(listOf("yellow", "green", "blue", "pink")[item.itemId - SELECTION_ACTION_COLOR_BASE])})"
                    else -> return original.onActionItemClicked(mode, item)
                }
                evaluateJavascript(action, null)
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                if (selectionActionMode === mode) selectionActionMode = null
                original.onDestroyActionMode(mode)
            }

            override fun onGetContentRect(mode: android.view.ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
                if (original is android.view.ActionMode.Callback2) original.onGetContentRect(mode, view, outRect)
                else outRect.set(0, 0, view.width, view.height)
            }
        }

    private fun populateMenu(menu: android.view.Menu) {
        menu.clear()
        menu.add(android.view.Menu.NONE, SELECTION_ACTION_COPY, 0, "Copiar")
        menu.add(android.view.Menu.NONE, SELECTION_ACTION_HIGHLIGHT, 1, "Grifar seleção").isEnabled = pendingHighlightRequestId == null
        val colors = menu.addSubMenu(android.view.Menu.NONE, SELECTION_ACTION_COLORS, 2, "Mudar cor do grifo")
        listOf("Amarelo", "Verde", "Azul", "Rosa").forEachIndexed { index, label ->
            colors.add(android.view.Menu.NONE, SELECTION_ACTION_COLOR_BASE + index, index, label)
        }
        menu.add(android.view.Menu.NONE, SELECTION_ACTION_REMOVE, 3, "Remover grifo")
        menu.findItem(SELECTION_ACTION_REMOVE).isVisible = selectedTextHasHighlight
        menu.findItem(SELECTION_ACTION_COLORS).isVisible = selectedTextHasHighlight
    }
}

private class BookWebViewClient(
    private val bookId: String,
    private val root: File,
    private val chapters: List<PublicationChapter>,
    private val currentChapter: () -> Int,
    private val progression: () -> Float,
    private val onNavigate: (Int) -> Unit,
    private val style: () -> ReaderWebStyle,
    private val highlights: () -> List<BookHighlightEntity>,
    private val pendingHighlight: () -> BookHighlightEntity?,
    private val onPendingHighlightConsumed: (String) -> Unit,
    private val onChapterReady: (WebView) -> Unit,
    private val onChapterLoadStarted: () -> Unit,
    private val onChapterLoadError: (String) -> Unit,
) : WebViewClient() {
    private val prefix = "/book/$bookId/"
    private val canonicalRoot = root.canonicalFile

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.scheme != "https" || uri.host != "reader.estante.local" || !uri.encodedPath.orEmpty().startsWith(prefix)) {
            return response("text/plain", "Conteúdo externo bloqueado.", 403)
        }
        val relative = runCatching { URLDecoder.decode(uri.encodedPath.orEmpty().removePrefix(prefix).replace("+", "%2B"), "UTF-8") }.getOrDefault("")
        val file = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
            ?: return response("text/plain", "Caminho inválido.", 404)
        if (!file.path.startsWith(canonicalRoot.path + File.separator) || !file.isFile || file.length() > 24L * 1024 * 1024) {
            return response("text/plain", "Arquivo não encontrado.", 404)
        }
        val mimeType = PublicationResourceMimeType.resolve(relative, chapters)
        return runCatching {
            // Standards mode is required for viewport sizing and horizontal page overflow.
            // This also repairs chapters imported by older versions without a doctype.
            val content = if (mimeType == "text/html") {
                java.io.SequenceInputStream("<!doctype html>\n".byteInputStream(), file.inputStream())
            } else file.inputStream()
            WebResourceResponse(mimeType, "UTF-8", content)
        }.getOrElse { response("text/plain", "Não foi possível carregar este recurso.", 404) }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.host != "reader.estante.local") return true
        val relative = runCatching { URLDecoder.decode(uri.encodedPath.orEmpty().removePrefix(prefix).replace("+", "%2B"), "UTF-8") }.getOrDefault("")
        val chapter = chapters.indexOfFirst { it.href == relative }
        if (chapter >= 0 && chapter != currentChapter()) {
            onNavigate(chapter)
            return true
        }
        return !uri.encodedPath.orEmpty().startsWith(prefix)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        view?.apply {
            val chapter = currentChapter()
            val target = pendingHighlight()?.takeIf { it.chapterIndex == chapter }
            val savedProgress = progression()
            applyReaderStyle(style(), chapter, highlights(), target, onPendingHighlightConsumed)
            postDelayed({
                if (this.url == url) {
                    if (target == null) scrollToProgress(savedProgress) { onChapterReady(this) }
                    else onChapterReady(this)
                }
            }, 120)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        if (url?.startsWith("https://reader.estante.local$prefix") == true) onChapterLoadStarted()
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest,
        error: android.webkit.WebResourceError,
    ) {
        if (request.isForMainFrame) {
            onChapterLoadError("O conteúdo do capítulo não pôde ser carregado. Verifique se o arquivo do livro ainda está disponível.")
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            onChapterLoadError("O arquivo deste capítulo não foi encontrado. Reimporte o livro para tentar novamente.")
        }
    }

    private fun response(mime: String, body: String, status: Int) = WebResourceResponse(
        mime,
        "UTF-8",
        status,
        if (status == 403) "Blocked" else "Not Found",
        mapOf("Cache-Control" to "no-store"),
        body.byteInputStream(),
    )
}

internal fun WebView.applyReaderStyle(
    style: ReaderWebStyle,
    chapterIndex: Int,
    highlights: List<BookHighlightEntity>,
    pendingHighlight: BookHighlightEntity?,
    onPendingHighlightConsumed: (String) -> Unit,
) {
    val background = style.background.color.toArgb().toUInt().toString(16).padStart(8, '0').takeLast(6)
    val text = if (style.background == ReaderCanvasBackground.DARK) "#F2EEE6" else "#292722"
    val columnCount = if (style.twoColumns) 2 else 1
    val css = """
        html{height:100%;overflow:hidden!important;font-size:${style.fontSizeSp}px!important;-webkit-text-size-adjust:100%;text-size-adjust:100%;}
        html,body{background:#$background!important;color:$text!important;}
        body{font-family:Georgia,'Noto Serif',serif!important;font-size:1rem!important;line-height:${style.lineSpacing}!important;width:100vw!important;max-width:none!important;height:100vh!important;min-height:0!important;margin:0!important;box-sizing:border-box;}
        #estante-content{font-size:1rem;line-height:${style.lineSpacing};overflow-wrap:anywhere;}
        #estante-content :is(p,li,dt,dd,blockquote){font-size:1rem!important;line-height:${style.lineSpacing}!important;}
        #estante-content :is(p,li,dt,dd,blockquote) :is(span,a,strong,em,b,i){font-size:inherit!important;line-height:inherit!important;}
        #estante-content .estante-prose{text-align:start!important;}
        #estante-content :is(h1,h2,h3,h4,h5,h6,[data-estante-heading]){line-height:1.25!important;overflow-wrap:anywhere;hyphens:auto;break-after:avoid;}
        #estante-content :is(h1,[data-estante-heading=large]){font-size:1.8rem!important;}
        #estante-content :is(h2,[data-estante-heading=medium]){font-size:1.4rem!important;}
        #estante-content :is(h3,h4,h5,h6){font-size:1.15rem!important;}
        #estante-content :is(p,li){orphans:2;widows:2;}
        img,svg,video{max-width:100%!important;max-height:calc(100vh - 56px)!important;height:auto!important;object-fit:contain;break-inside:avoid;}
        pre{white-space:pre-wrap!important;}table{max-width:100%;}a{color:#A95138!important;word-break:normal!important;}
        .estante-highlight{color:inherit!important;box-decoration-break:clone;-webkit-box-decoration-break:clone;border-radius:.15em;-webkit-tap-highlight-color:transparent;}
        .estante-highlight-focus{outline:2px solid #A95138;outline-offset:2px;}
    """.trimIndent()

    val chapterHighlights = highlights.asSequence().filter { it.chapterIndex == chapterIndex }.map { item ->
        org.json.JSONObject()
            .put("id", item.id)
            .put("start", item.startOffset)
            .put("end", item.endOffset)
            .put("text", item.selectedText)
            .put("prefix", item.prefix)
            .put("suffix", item.suffix)
            .put("color", com.estante.reader.core.publication.BookHighlightColor.fromId(item.colorId).hex)
    }.fold(org.json.JSONArray()) { array, value -> array.put(value) }
    val encodedHighlights = org.json.JSONObject.quote(chapterHighlights.toString())
    val target = pendingHighlight?.takeIf { it.chapterIndex == chapterIndex }?.id?.let(org.json.JSONObject::quote) ?: "null"
    val script = """
        (function(){
          var previousProgress=window.estanteProgressValue,hadLayout=!!window.estanteLayoutPages;
          // Publisher paragraphs can be tiny body copy or enormous display titles.
          // Classify once before reader CSS overrides them, preserving semantic emphasis.
          if(!window.estanteTypographyReady){
            document.querySelectorAll('p').forEach(function(p){
              var text=p.textContent.trim(),size=parseFloat(getComputedStyle(p).fontSize),base=parseFloat(getComputedStyle(document.body).fontSize)||16;
              if(text.length>180)p.classList.add('estante-prose');
              else if(size/base>1.25)p.dataset.estanteHeading=size/base>2?'large':'medium';
            });
            if(!document.documentElement.lang&&document.documentElement.getAttribute('xml:lang'))document.documentElement.lang=document.documentElement.getAttribute('xml:lang');
            window.estanteTypographyReady=true;
          }
          var viewport=document.querySelector('meta[name=viewport]');if(!viewport){viewport=document.createElement('meta');viewport.name='viewport';document.head.appendChild(viewport);}viewport.content='width=device-width,initial-scale=1';
          var style=document.getElementById('estante-reader-style');
          if(!style){style=document.createElement('style');style.id='estante-reader-style';document.head.appendChild(style);}
          var pager=document.getElementById('estante-pages'),content=document.getElementById('estante-content');
          if(!pager){pager=document.createElement('div');pager.id='estante-pages';content=document.createElement('div');content.id='estante-content';while(document.body.firstChild)content.appendChild(document.body.firstChild);pager.appendChild(content);document.body.appendChild(pager);}
          function layoutPages(){var v=window.visualViewport;var w=v?v.width:document.documentElement.clientWidth;var h=v?v.height:document.documentElement.clientHeight;window.estantePageWidth=Math.max(1,w);style.textContent=${org.json.JSONObject.quote(css)}+'html,body{width:'+w+'px!important;height:'+h+'px!important;}body{padding:0!important;column-count:auto!important;overflow:hidden!important;}#estante-pages{position:relative;width:100%;height:100%;overflow:hidden;}#estante-content{width:100%;height:100%;box-sizing:border-box;padding:28px ${style.marginsDp}px;column-count:$columnCount;column-gap:${style.marginsDp * 2}px;column-fill:auto;}img,svg,video{max-height:'+Math.max(1,h-56)+'px!important;}';}
          window.estanteLayoutPages=layoutPages;layoutPages();
          if(window.estanteProgressTimer){clearInterval(window.estanteProgressTimer);}
          function pageCount(){var count=Math.max(1,Math.ceil((content.scrollWidth-1)/window.estantePageWidth));var end=document.getElementById('estante-page-end');if(!end){end=document.createElement('div');end.id='estante-page-end';end.setAttribute('aria-hidden','true');pager.appendChild(end);}end.style.cssText='position:absolute;top:0;left:'+(count*window.estantePageWidth-1)+'px;width:1px;height:1px;pointer-events:none;';return count;}
          function reportPage(){if(window.estantePreviewActive)return;var count=pageCount(),page=Math.min(count-1,Math.round(pager.scrollLeft/window.estantePageWidth));window.estanteProgressValue=count>1?page/(count-1):0;EstanteProgress.onPagination(page+1,count);EstanteProgress.onProgress($chapterIndex,window.estanteProgressValue);}
          window.estanteRestoreProgress=function(p){window.estantePreviewActive=false;var page=Math.round(Math.max(0,Math.min(1,p))*(pageCount()-1));pager.scrollTo({left:page*window.estantePageWidth,top:0,behavior:'instant'});reportPage();requestAnimationFrame(function(){if(EstanteProgress.onReady)EstanteProgress.onReady();});};
          window.estanteCommitTurnPage=function(direction,preview){var next=Math.round(pager.scrollLeft/window.estantePageWidth)+direction,count=pageCount();if(next<0||next>=count){window.estantePreviewActive=true;return 'boundary';}window.estantePreviewActive=!!preview;pager.scrollTo({left:next*window.estantePageWidth,top:0,behavior:'instant'});reportPage();return 'turned';};
          window.estanteConfirmTurnPage=function(){window.estantePreviewActive=false;reportPage();};
          window.estanteTurnPage=function(direction){if(!getSelection().isCollapsed||(EstanteProgress.isTurning&&EstanteProgress.isTurning()))return;if(EstanteProgress.onTurnRequested){EstanteProgress.onTurnRequested(direction);}else if(window.estanteCommitTurnPage(direction)==='boundary'){EstanteProgress.onChapterBoundary(direction);}};
          window.estanteProgressTimer=setInterval(reportPage,1200);
          if(!window.estanteResizeHandler){window.estanteResizeHandler=function(){var p=window.estanteProgressValue||0;window.estanteLayoutPages();requestAnimationFrame(function(){window.estanteRestoreProgress(p);});};addEventListener('resize',window.estanteResizeHandler);}
          if(hadLayout&&previousProgress!=null&&$target===null)requestAnimationFrame(function(){window.estanteRestoreProgress(previousProgress);});
          requestAnimationFrame(function(){EstanteProgress.onPagination(Math.round(pager.scrollLeft/window.estantePageWidth)+1,pageCount());});
          document.querySelectorAll('[data-estante-highlight]').forEach(function(mark){var parent=mark.parentNode;if(!parent)return;while(mark.firstChild)parent.insertBefore(mark.firstChild,mark);parent.removeChild(mark);});
          // Do not normalize adjacent text nodes: selection offsets are based on this order,
          // and normalizing while Android owns an active selection invalidates its handles.
          var items=JSON.parse($encodedHighlights);
          function textWalker(){return document.createTreeWalker(content,NodeFilter.SHOW_TEXT,{acceptNode:function(node){return node.parentElement&&node.parentElement.closest('script,style,noscript,template,[aria-hidden=true]')?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT;}});}
          function readFlatText(){var walker=textWalker(),all='',node;while((node=walker.nextNode()))all+=node.nodeValue;return all;}
          function offsetAt(containerNode,offset){
            var walker=textWalker(),cursor=0,node,boundary=document.createRange();
            boundary.setStart(containerNode,offset);boundary.collapse(true);
            while((node=walker.nextNode())){
              var part=document.createRange();part.selectNodeContents(node);
              if(node===containerNode)return cursor+Math.max(0,Math.min(node.nodeValue.length,offset));
              if(boundary.compareBoundaryPoints(Range.START_TO_START,part)<=0)return cursor;
              if(boundary.compareBoundaryPoints(Range.END_TO_END,part)>=0){cursor+=node.nodeValue.length;continue;}
              try{part.setEnd(containerNode,offset);cursor+=part.toString().length;}catch(e){}
              return cursor;
            }
            return cursor;
          }
          function rangeFor(start,end){
            var walker=textWalker();
            var cursor=0,startNode=null,endNode=null,startLocal=0,endLocal=0,node;
            while((node=walker.nextNode())){var length=node.nodeValue.length;if(!startNode&&start>=cursor&&start<cursor+length){startNode=node;startLocal=start-cursor;}if(!endNode&&end>cursor&&end<=cursor+length){endNode=node;endLocal=end-cursor;}cursor+=length;if(startNode&&endNode)break;}
            if(!startNode||!endNode)return null;
            var range=document.createRange();range.setStart(startNode,startLocal);range.setEnd(endNode,endLocal);return range;
          }
          function locateItem(item,flat){
            var start=item.start,end=item.end;
            if(flat.slice(start,end)!==item.text){
              var matches=[],from=0;
              while((from=flat.indexOf(item.text,from))!==-1){matches.push(from++);}
              if(!matches.length)return null;
              matches.sort(function(a,b){var score=function(pos){var before=flat.slice(Math.max(0,pos-item.prefix.length),pos),after=flat.slice(pos+item.text.length,pos+item.text.length+item.suffix.length);return commonSuffix(before,item.prefix)+commonPrefix(after,item.suffix);};return score(b)-score(a);});
              if(matches.length>1&&commonScore(flat,matches[0],item)<=commonScore(flat,matches[1],item))return null;
              start=matches[0];end=start+item.text.length;
            }
            return rangeFor(start,end);
          }
          function commonSuffix(a,b){var i=0;while(i<a.length&&i<b.length&&a[a.length-1-i]===b[b.length-1-i])i++;return i;}
          function commonPrefix(a,b){var i=0;while(i<a.length&&i<b.length&&a[i]===b[i])i++;return i;}
          function commonScore(flat,start,item){return commonSuffix(flat.slice(Math.max(0,start-item.prefix.length),start),item.prefix)+commonPrefix(flat.slice(start+item.text.length,start+item.text.length+item.suffix.length),item.suffix);}
          var flatText=readFlatText();
          items.sort(function(a,b){return a.start-b.start;}).forEach(function(item){
            var range=locateItem(item,flatText);if(!range||range.collapsed)return;
            var span=document.createElement('span');span.className='estante-highlight';span.dataset.estanteHighlight=item.id;span.style.backgroundColor=item.color+'99';span.appendChild(range.extractContents());range.insertNode(span);
          });
          if($target){
            var focused=document.querySelector('[data-estante-highlight="'+$target+'"]');
            if(focused){focused.classList.add('estante-highlight-focus');focused.scrollIntoView({block:'nearest',inline:'start'});pager.scrollTo({left:Math.floor(pager.scrollLeft/window.estantePageWidth)*window.estantePageWidth,top:0});reportPage();setTimeout(function(){focused.classList.remove('estante-highlight-focus');},1500);EstanteHighlights.onHighlightVisible($target);}
          }
          function selectionHref(){var path=decodeURIComponent(location.pathname||'');var parts=path.split('/');return parts.length>3&&parts[1]==='book'?parts.slice(3).join('/'):'';}
          function selectedMark(range){var node=range.commonAncestorContainer;var el=node.nodeType===Node.ELEMENT_NODE?node:node.parentElement;var mark=el&&el.closest('[data-estante-highlight]');if(mark)return mark;var marks=content.querySelectorAll('[data-estante-highlight]');for(var i=0;i<marks.length;i++){try{if(range.intersectsNode(marks[i]))return marks[i];}catch(e){}}return null;}
          window.estantePendingHighlightRequests=window.estantePendingHighlightRequests||{};
          window.estanteHighlightResult=function(requestId,result){var pending=window.estantePendingHighlightRequests[requestId];if(!pending)return 'stale';delete window.estantePendingHighlightRequests[requestId];if(result==='saved'){var selection=getSelection();if(selection&&selection.rangeCount===1&&pending.range){var current=selection.getRangeAt(0);if(current.compareBoundaryPoints(Range.START_TO_START,pending.range)===0&&current.compareBoundaryPoints(Range.END_TO_END,pending.range)===0)selection.removeAllRanges();}var snapshot=window.estanteSelectionSnapshot;if(snapshot&&snapshot.start===pending.start&&snapshot.end===pending.end&&snapshot.href===pending.href)window.estanteSelectionSnapshot=null;}return result;};
          window.estanteConfirmHighlight=function(requestId){var selection=getSelection(),s=null,r=null;if(selection&&!selection.isCollapsed&&selection.rangeCount){r=selection.getRangeAt(0).cloneRange();var st=offsetAt(r.startContainer,r.startOffset),en=offsetAt(r.endContainer,r.endOffset),text=readFlatText().slice(st,en);s={start:st,end:en,text:text,prefix:readFlatText().slice(Math.max(0,st-80),st),suffix:readFlatText().slice(en,en+80),href:selectionHref(),mark:selectedMark(r)?.dataset.estanteHighlight||null,range:r};window.estanteSelectionSnapshot=s;}else{s=window.estanteSelectionSnapshot;r=s&&s.range;}
            var flat=readFlatText();if(!requestId)requestId='direct-'+Date.now()+'-'+Math.random().toString(36).slice(2);if(!s||s.mark||s.text.trim().length<2||s.text.length>5000||s.end<=s.start||s.end>flat.length||flat.slice(s.start,s.end)!==s.text||typeof EstanteHighlights.onTextSelection!=='function')return 'invalid';
            window.estantePendingHighlightRequests[requestId]={start:s.start,end:s.end,href:s.href,range:r?r.cloneRange():null};EstanteHighlights.onTextSelection(requestId,s.href,s.text,s.start,s.end,s.prefix,s.suffix);return 'pending';};
          window.estanteRemoveSelectedHighlight=function(){var s=window.estanteSelectionSnapshot,selection=getSelection(),mark=s&&s.mark;if(!mark&&selection&&selection.rangeCount){var found=selectedMark(selection.getRangeAt(0));mark=found&&found.dataset.estanteHighlight;}if(mark)EstanteHighlights.onRemoveHighlight(mark);window.estanteSelectionSnapshot=null;if(selection)selection.removeAllRanges();return mark||'';};
          window.estanteChangeSelectedHighlightColor=function(color){var s=window.estanteSelectionSnapshot,selection=getSelection(),mark=s&&s.mark;if(!mark&&selection&&selection.rangeCount){var found=selectedMark(selection.getRangeAt(0));mark=found&&found.dataset.estanteHighlight;}if(mark)EstanteHighlights.onChangeHighlightColor(mark,color);window.estanteSelectionSnapshot=null;if(selection)selection.removeAllRanges();return mark||'';};
          window.estanteCopySelection=function(){var selection=getSelection(),text=selection&&!selection.isCollapsed?String(selection):window.estanteSelectionSnapshot?.text;if(text)EstanteHighlights.onCopyText(text);};
          function reportSelection(){
            var selection=window.getSelection();if(!selection||!selection.rangeCount){window.estanteSelectionSnapshot=null;EstanteHighlights.onSelectionHasHighlight(false);return;}
            var range=selection.getRangeAt(0);if(range.collapsed||!content.contains(range.commonAncestorContainer)){window.estanteSelectionSnapshot=null;EstanteHighlights.onSelectionHasHighlight(false);return;}
            var start=offsetAt(range.startContainer,range.startOffset);var end=offsetAt(range.endContainer,range.endOffset);
            var text=readFlatText().slice(start,end);if(start<0||end<=start||text.trim().length<2||text.length>5000){window.estanteSelectionSnapshot=null;EstanteHighlights.onSelectionHasHighlight(false);return;}
            var mark=selectedMark(range),href=selectionHref();
            var flat=readFlatText();window.estanteSelectionSnapshot={start:start,end:end,text:text,prefix:flat.slice(Math.max(0,start-80),start),suffix:flat.slice(end,end+80),href:href,mark:mark?mark.dataset.estanteHighlight:null,range:range.cloneRange()};
            EstanteHighlights.onSelectionHasHighlight(!!mark);
          }
          window.estanteReportSelection=reportSelection;
          if(!window.estanteSelectionListener){window.estanteSelectionListener=function(){var current=window.getSelection();if(!current||!current.rangeCount||current.isCollapsed){window.estanteSelectionSnapshot=null;EstanteHighlights.onSelectionHasHighlight(false);}clearTimeout(window.estanteSelectionTimer);window.estanteSelectionTimer=setTimeout(function(){window.estanteReportSelection&&window.estanteReportSelection();},60);};document.addEventListener('selectionchange',window.estanteSelectionListener);}
        })();
    """.trimIndent()
    evaluateJavascript(script, null)
}

private fun WebView.scrollToProgress(progression: Float, onRestored: () -> Unit = {}) {
    evaluateJavascript(
        "window.estanteRestoreProgress?.(${progression.coerceIn(0f, 1f)});",
        { onRestored() },
    )
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
