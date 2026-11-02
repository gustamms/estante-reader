package com.estante.reader

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.io.File
import java.net.URLConnection
import java.net.URLDecoder
import java.util.Locale
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
    onTextSelection: (String, String, Int, Int, String, String) -> Unit,
    onHighlightColorChange: (String) -> Unit,
    onHighlightItemColorChange: (String, String) -> Unit,
    onOpenHighlight: (String) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onRestoreHighlight: (BookHighlightEntity) -> Unit,
    onPendingHighlightConsumed: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenSearchResult: (PublicationSearchResult) -> Unit,
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
    val setControls: (Boolean) -> Unit = { visible -> controlsVisible = visible }
    val latestToggleControls by rememberUpdatedState(setControls)
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val latestHighlights by rememberUpdatedState(highlights)
    val latestPendingHighlight by rememberUpdatedState(pendingHighlight)
    val latestOnTextSelection by rememberUpdatedState(onTextSelection)
    val latestOnPendingConsumed by rememberUpdatedState(onPendingHighlightConsumed)
    val webView = remember(bookId) { mutableStateOf<WebView?>(null) }
    val snackbarHostState = remember(bookId) { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

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
            WebView(viewContext).apply {
                setBackgroundColor(background.color.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(BookProgressBridge { reportedChapter, value ->
                    latestOnProgress(reportedChapter, value)
                }, "EstanteProgress")
                addJavascriptInterface(BookSelectionBridge(
                    reportSelection = { href, text, start, end, prefix, suffix ->
                        latestOnTextSelection(href, text, start, end, prefix, suffix)
                        coroutineScope.launch { snackbarHostState.showSnackbar("Trecho grifado neste livro") }
                    },
                    reportVisible = { id -> latestOnPendingConsumed(id) },
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
                )
                var downX = 0f
                var downY = 0f
                var downTime = 0L
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            downTime = event.eventTime
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            val moved = kotlin.math.abs(event.x - downX) > 12 || kotlin.math.abs(event.y - downY) > 12
                            val quickTap = event.eventTime - downTime < 280
                            if (quickTap && !moved && event.x in width * 0.30f..width * 0.70f && event.y in height * 0.34f..height * 0.68f) {
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

    DisposableEffect(webView.value) {
        onDispose {
            webView.value?.apply {
                stopLoading()
                removeJavascriptInterface("EstanteProgress")
                removeJavascriptInterface("EstanteHighlights")
                destroy()
            }
        }
    }

    LaunchedEffect(fontSizeSp, lineSpacing, marginsDp, twoColumns, background, chapterIndex) {
        delay(250)
        webView.value?.applyReaderStyle(
            ReaderWebStyle(fontSizeSp, twoColumns, background, lineSpacing, marginsDp),
            chapterIndex,
            highlights,
            pendingHighlight,
        ) { id -> onPendingHighlightConsumed(id) }
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
                Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { latestOnGoToChapter((chapterIndex - 1).coerceAtLeast(0)) },
                        enabled = chapterIndex > 0,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFA95138)),
                    ) { Text("Anterior") }
                    Text(
                        text = "${chapterIndex + 1} / $chapterCount  ·  ${(progression * 100).toInt()}%",
                        color = Color(0xFF292722), modifier = Modifier.weight(1f), fontSize = 13.sp,
                    )
                    TextButton(
                        onClick = { latestOnGoToChapter((chapterIndex + 1).coerceAtMost(chapterCount - 1)) },
                        enabled = chapterIndex < chapterCount - 1,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFA95138)),
                    ) { Text("Próximo") }
                }
                val totalProgress = ((chapterIndex + progression) / chapterCount).coerceIn(0f, 1f)
                Slider(
                    value = totalProgress,
                    onValueChange = { value ->
                        val absolute = value * chapterCount
                        val newIndex = absolute.toInt().coerceIn(0, chapterCount - 1)
                        val newProgress = if (newIndex == chapterCount - 1 && value >= 1f) 1f else absolute - newIndex
                        if (newIndex == chapterIndex) {
                            webView.value?.evaluateJavascript(
                                "window.scrollTo(0, Math.max(0, (document.documentElement.scrollHeight - window.innerHeight) * $newProgress));",
                                null,
                            )
                        }
                        latestOnProgress(newIndex, newProgress)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(color = Color(0xFFE4DED2))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { showContents = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA95138))) {
                        Text("Sumário")
                    }
                    TextButton(onClick = { showSearch = !showSearch; searchQuery = "" }) { Text("Buscar") }
                    TextButton(onClick = { onAddBookmark(); showBookmarks = true }) { Text("Marcar") }
                    TextButton(onClick = { showBookmarks = true }) { Text("Marcadores ${bookmarks.size}") }
                    TextButton(onClick = { showHighlightsPanel = true; controlsVisible = false }) { Text("Grifos ${highlights.size}") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Fundo", color = Color(0xFF706B62), fontSize = 12.sp)
                    ReaderCanvasBackground.entries.forEach { option ->
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(option.color)
                                .border(if (option == background) 2.dp else 1.dp, if (option == background) Color(0xFFA95138) else Color(0xFFAEA99F), RoundedCornerShape(50))
                                .clickable { onBackgroundChange(option) }
                        )
                    }
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

private data class ReaderWebStyle(
    val fontSizeSp: Float,
    val twoColumns: Boolean,
    val background: ReaderCanvasBackground,
    val lineSpacing: Float,
    val marginsDp: Float,
)

private class BookProgressBridge(private val report: (Int, Float) -> Unit) {
    @JavascriptInterface
    fun onProgress(chapterIndex: Int, progression: Float) {
        report(chapterIndex, progression)
    }
}

private class BookSelectionBridge(
    private val reportSelection: (String, String, Int, Int, String, String) -> Unit,
    private val reportVisible: (String) -> Unit,
) {
    @JavascriptInterface
    fun onTextSelection(
        chapterHref: String,
        selectedText: String,
        startOffset: Int,
        endOffset: Int,
        prefix: String,
        suffix: String,
    ) {
        if (selectedText.length in 2..5000 && startOffset >= 0 && endOffset > startOffset) {
            reportSelection(chapterHref.take(2048), selectedText.take(5000), startOffset, endOffset, prefix.takeLast(80), suffix.take(80))
        }
    }

    @JavascriptInterface
    fun onHighlightVisible(id: String) {
        if (id.length <= 64) reportVisible(id)
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
        val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: when (file.extension.lowercase(Locale.ROOT)) {
            "xhtml", "html", "htm" -> "application/xhtml+xml"
            "opf", "ncx", "xml" -> "application/xml"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "woff", "woff2", "ttf", "otf" -> "font/${file.extension.lowercase(Locale.ROOT)}"
            else -> "application/octet-stream"
        }
        return runCatching {
            WebResourceResponse(mimeType, "UTF-8", file.inputStream())
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
            applyReaderStyle(style(), chapter, highlights(), target, onPendingHighlightConsumed)
            if (target == null) postDelayed({ scrollToProgress(progression()) }, 120)
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

private fun WebView.applyReaderStyle(
    style: ReaderWebStyle,
    chapterIndex: Int,
    highlights: List<BookHighlightEntity>,
    pendingHighlight: BookHighlightEntity?,
    onPendingHighlightConsumed: (String) -> Unit,
) {
    val background = style.background.color.toArgb().toUInt().toString(16).padStart(8, '0').takeLast(6)
    val text = if (style.background == ReaderCanvasBackground.DARK) "#F2EEE6" else "#292722"
    val columns = if (style.twoColumns) "column-count:2;column-gap:30px;" else "column-count:1;"
    val css = """html,body{background:#$background!important;color:$text!important;}body{font-family:Georgia,'Noto Serif',serif!important;font-size:${style.fontSizeSp}px!important;line-height:${style.lineSpacing}!important;max-width:900px;margin:0 auto!important;padding:28px ${style.marginsDp}px 120px!important;box-sizing:border-box;$columns}img,svg,video{max-width:100%!important;height:auto!important;}a{color:#A95138!important;}.estante-highlight{color:inherit!important;box-decoration-break:clone;-webkit-box-decoration-break:clone;border-radius:.15em;-webkit-tap-highlight-color:transparent;}.estante-highlight-focus{outline:2px solid #A95138;outline-offset:2px;}"""
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
          var style=document.getElementById('estante-reader-style');
          if(!style){style=document.createElement('style');style.id='estante-reader-style';document.head.appendChild(style);}
          style.textContent=${org.json.JSONObject.quote(css)};
          if(window.estanteProgressTimer){clearInterval(window.estanteProgressTimer);}
          window.estanteProgressTimer=setInterval(function(){var e=document.documentElement;var h=Math.max(0,e.scrollHeight-window.innerHeight);var p=h>0?window.scrollY/h:0;EstanteProgress.onProgress($chapterIndex,Math.max(0,Math.min(1,p)));},1200);
          document.querySelectorAll('[data-estante-highlight]').forEach(function(mark){var parent=mark.parentNode;if(!parent)return;while(mark.firstChild)parent.insertBefore(mark.firstChild,mark);parent.removeChild(mark);});
          if(document.body)document.body.normalize();
          var items=JSON.parse($encodedHighlights);
          function rangeFor(start,end){
            var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(node){return node.parentElement&&node.parentElement.closest('script,style,noscript,template')?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT;}});
            var cursor=0,startNode=null,endNode=null,startLocal=0,endLocal=0,node;
            while((node=walker.nextNode())){var length=node.nodeValue.length;if(!startNode&&start>=cursor&&start<cursor+length){startNode=node;startLocal=start-cursor;}if(!endNode&&end>cursor&&end<=cursor+length){endNode=node;endLocal=end-cursor;}cursor+=length;if(startNode&&endNode)break;}
            if(!startNode||!endNode)return null;
            var range=document.createRange();range.setStart(startNode,startLocal);range.setEnd(endNode,endLocal);return range;
          }
          items.sort(function(a,b){return a.start-b.start;}).forEach(function(item){
            var range=rangeFor(item.start,item.end);if(!range||range.collapsed)return;
            var span=document.createElement('span');span.className='estante-highlight';span.dataset.estanteHighlight=item.id;span.style.backgroundColor=item.color+'99';span.appendChild(range.extractContents());range.insertNode(span);
          });
          if($target){
            var focused=document.querySelector('[data-estante-highlight="'+$target+'"]');
            if(focused){focused.classList.add('estante-highlight-focus');focused.scrollIntoView({block:'center',behavior:'smooth'});setTimeout(function(){focused.classList.remove('estante-highlight-focus');},1500);EstanteHighlights.onHighlightVisible($target);}
          }
          function selectionHref(){var path=decodeURIComponent(location.pathname||'');var parts=path.split('/');return parts.length>3&&parts[1]==='book'?parts.slice(3).join('/'):'';}
          function offsetAt(container,offset){var range=document.createRange();range.selectNodeContents(document.body);range.setEnd(container,offset);return range.toString().length;}
          function reportSelection(){
            var selection=window.getSelection();if(!selection||!selection.rangeCount)return;
            var quote=String(selection).trim();if(quote.length<2||quote.length>5000)return;
            var range=selection.getRangeAt(0);if(range.collapsed)return;
            var start=offsetAt(range.startContainer,range.startOffset);var end=offsetAt(range.endContainer,range.endOffset);
            if(end<=start)return;
            var before=document.createRange();before.selectNodeContents(document.body);before.setEnd(range.startContainer,range.startOffset);
            var after=document.createRange();after.selectNodeContents(document.body);after.setStart(range.endContainer,range.endOffset);
            var prefix=before.toString().slice(-80);var suffix=after.toString().slice(0,80);var href=selectionHref();
            var key=href+'|'+start+'|'+end+'|'+quote;if(key===window.estanteLastSelection)return;window.estanteLastSelection=key;
            EstanteHighlights.onTextSelection(href,quote,start,end,prefix,suffix);
            setTimeout(function(){var current=window.getSelection();if(current)current.removeAllRanges();},0);
          }
          function scheduleSelection(){clearTimeout(window.estanteSelectionTimer);window.estanteSelectionTimer=setTimeout(reportSelection,450);}
          if(!window.estanteSelectionListener){document.addEventListener('selectionchange',scheduleSelection);document.addEventListener('mouseup',scheduleSelection);document.addEventListener('touchend',function(){setTimeout(scheduleSelection,180);});window.estanteSelectionListener=true;}
        })();
    """.trimIndent()
    evaluateJavascript(script, null)
}

private fun WebView.scrollToProgress(progression: Float) {
    evaluateJavascript(
        "window.scrollTo(0, Math.max(0, (document.documentElement.scrollHeight - window.innerHeight) * ${progression.coerceIn(0f, 1f)}));",
        null,
    )
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
