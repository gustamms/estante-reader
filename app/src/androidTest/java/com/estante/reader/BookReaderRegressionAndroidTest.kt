package com.estante.reader

import android.content.Intent
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.estante.reader.core.publication.EpubImporter
import com.estante.reader.core.data.BookHighlightEntity
import com.estante.reader.core.data.BookHighlightRepository
import com.estante.reader.core.data.ComicDatabase
import com.estante.reader.core.data.ComicEntity
import com.estante.reader.core.data.ComicFormat
import com.estante.reader.core.data.ComicImportState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private typealias HighlightSaveCallback = (String, String, String, Int, Int, String, String, (BookHighlightSaveResult) -> Unit) -> Unit

@OptIn(eu.wewox.pagecurl.ExperimentalPageCurlApi::class)
class BookReaderRegressionAndroidTest {
    @Test fun publisherStylesRespectReaderSettingsAndLongParagraphsStayOnPages() {
        ReaderHarness().use { h ->
            h.load(SAMPLE)
            for ((width, double) in listOf(360 to false, 800 to true)) {
                h.resize(width, 740)
                for (font in listOf(20f, 32f)) {
                    h.style(font, double)
                    val metrics = JSONObject(h.js("""(function(){var p=document.querySelector('p.prose'),t=document.querySelector('p.title');return {font:parseFloat(getComputedStyle(p).fontSize),line:parseFloat(getComputedStyle(p).lineHeight),span:parseFloat(getComputedStyle(p.querySelector('span')).fontSize),align:getComputedStyle(p).textAlign,title:parseFloat(getComputedStyle(t).fontSize),width:estantePageWidth};})()"""))
                    assertEquals(font.toDouble(), metrics.getDouble("font"), 0.1)
                    assertEquals(font * 1.72, metrics.getDouble("line"), 0.1)
                    assertEquals(font.toDouble(), metrics.getDouble("span"), 0.1)
                    assertEquals("start", metrics.getString("align"))
                    assertTrue("Display titles must be bounded", metrics.getDouble("title") <= font * 1.81)
                    h.js("estanteRestoreProgress(0)")
                    // Every fragment of a multi-page paragraph must fit inside a column,
                    // with whole lines inside the usable vertical area.
                    val overflow = h.js("""(function(){var r=document.createRange();r.selectNodeContents(document.querySelector('p.prose'));return Array.from(r.getClientRects()).filter(function(r){return r.width>estantePageWidth/${if (double) 2 else 1}||r.top<27||r.bottom>innerHeight-27;}).length;})()""").toInt()
                    assertEquals("Long paragraph fragments must not clip at $width / $font", 0, overflow)
                    h.js("estanteRestoreProgress(.5)")
                    val old = h.js("estanteProgressValue").toDouble()
                    h.style(font + 2, double)
                    assertEquals("Font changes must keep the reading position", old, h.js("estanteProgressValue").toDouble(), 0.12)
                }
            }
        }
    }

    @Test fun openingAHighlightKeepsItsTargetAfterRepagination() {
        ReaderHarness().use { h ->
            h.load(SAMPLE)
            h.resize(360, 740)
            h.style(24f, false)
            h.js("window.EstanteHighlights={onHighlightVisible:function(){},onTextSelection:function(){}};estanteRestoreProgress(0)")
            val quote = org.json.JSONTokener(h.js("document.body.textContent.slice(5000,5020)")).nextValue().toString()
            val highlight = com.estante.reader.core.data.BookHighlightEntity(
                "test-highlight", "test", 0, "chapter.html", 5000, 5020, quote, "", "", "yellow", 0L,
            )
            h.focusHighlight(highlight)
            assertTrue("Highlight navigation must keep the target page visible", h.js("document.getElementById('estante-pages').scrollLeft").toDouble() > 0)
            val visible = JSONObject(h.js("""(function(){var r=document.querySelector('[data-estante-highlight]').getBoundingClientRect();return {left:r.left,right:r.right,width:innerWidth};})()"""))
            assertTrue(visible.getDouble("right") > 0 && visible.getDouble("left") < visible.getDouble("width"))
        }
    }

    @Test fun nativeCurlCompletesForwardBackwardAndAcrossChapters() {
        ReaderHarness(curl = true).use { h ->
            h.load(SAMPLE)
            h.style(24f, false)
            h.js("estanteRestoreProgress(0)")
            h.js("estanteTurnPage(1)")
            h.await("native curl starts") { h.controller.turn?.after != null }
            val first = requireNotNull(h.controller.turn)
            assertEquals(1, first.direction)
            assertFalse("Destination must contain the next page", first.before.sameAs(first.after))
            // Repeated requests during the animation must not skip a second page.
            h.js("estanteTurnPage(1)")
            h.await("Plugin must animate the curl") { first.animation.progress > 0.2f }
            h.deviceScreenshot("curl-mid.png")
            h.await("curl completes") { h.controller.turn == null }
            assertEquals("Plugin must complete its page turn", 1, first.animation.current)
            assertEquals(1, h.js("Math.round(document.getElementById('estante-pages').scrollLeft/estantePageWidth)").toInt())
            h.js("estanteTurnPage(-1)")
            h.await("backward curl starts") { h.controller.turn?.after != null }
            assertEquals(-1, h.controller.turn?.direction)
            h.await("backward curl completes") { h.controller.turn == null }
            assertEquals(0, h.js("document.getElementById('estante-pages').scrollLeft").toInt())
            h.js("estanteRestoreProgress(1)")
            h.js("estanteTurnPage(1)")
            h.await("chapter curl starts") { h.controller.turn?.after != null }
            assertTrue(h.js("location.pathname").contains("next.html"))
            h.await("chapter curl completes") { h.controller.turn == null }
            assertEquals(0.0, h.js("estanteProgressValue").toDouble(), 0.01)
        }
    }

    @Test fun draggingTracksTheHeldPointerAndCanReturnToTheOriginalPage() {
        ReaderHarness(curl = true).use { h ->
            h.load(SAMPLE); h.style(24f, false)
            h.js("estanteRestoreProgress(0)")
            h.pointer(android.view.MotionEvent.ACTION_DOWN, .9f, .75f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .85f, .73f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .8f, .71f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .7f, .68f)
            h.await("Drag destination is rendered") { h.controller.turn?.after != null }
            val turn = requireNotNull(h.controller.turn)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .52f, .62f)
            h.await("Curl follows pointer before release") { turn.animation.progress > .1f }
            repeat(6) { h.settle() }
            assertEquals("Holding must not automatically complete the page turn", 1, turn.animation.current)
            assertNotNull(h.controller.turn)
            assertEquals("A held preview must not save the next page", 0.0, h.js("estanteProgressValue").toDouble(), .001)
            val middle = turn.animation.progress
            h.deviceScreenshot("curl-drag-held.png")
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .25f, .58f)
            h.await("Moving farther must curl farther") { turn.animation.progress > middle + .08f }
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .82f, .73f)
            h.await("Returning the finger must uncurl the sheet") { turn.animation.progress < middle - .08f }
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .88f, .75f)
            h.settle()
            h.pointer(android.view.MotionEvent.ACTION_UP, .9f, .75f)
            h.await("Cancelled drag restores the original page") { h.controller.turn == null }
            assertEquals(0, h.js("Math.round(document.getElementById('estante-pages').scrollLeft)").toInt())
            assertEquals(0.0, h.js("estanteProgressValue").toDouble(), .001)
        }
    }

    @Test fun draggingCommitsBothDirectionsAndSystemCancellationRestoresProgress() {
        ReaderHarness(curl = true).use { h ->
            h.load(SAMPLE); h.style(24f, false)
            h.js("estanteRestoreProgress(0)")
            h.pointer(android.view.MotionEvent.ACTION_DOWN, .9f, .75f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .85f, .73f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .8f, .71f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .7f, .68f)
            h.await("Forward drag ready") { h.controller.turn?.after != null }
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .2f, .55f)
            h.settle()
            h.pointer(android.view.MotionEvent.ACTION_UP, .1f, .52f)
            h.await("Forward drag completes") { h.controller.turn == null }
            assertEquals(1, h.js("Math.round(document.getElementById('estante-pages').scrollLeft/estantePageWidth)").toInt())
            assertTrue(h.js("estanteProgressValue").toDouble() > 0)
            h.pointer(android.view.MotionEvent.ACTION_DOWN, .1f, .75f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .3f, .68f)
            h.await("Backward drag ready") { h.controller.turn?.after != null }
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .8f, .55f)
            h.settle()
            h.pointer(android.view.MotionEvent.ACTION_UP, .9f, .52f)
            h.await("Backward drag completes") { h.controller.turn == null }
            assertEquals(0, h.js("Math.round(document.getElementById('estante-pages').scrollLeft)").toInt())
            h.pointer(android.view.MotionEvent.ACTION_DOWN, .9f, .75f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .85f, .73f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .8f, .71f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .7f, .68f)
            h.await("Cancelled drag ready") { h.controller.turn?.after != null }
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .2f, .55f)
            h.settle()
            h.pointer(android.view.MotionEvent.ACTION_CANCEL, .2f, .55f)
            h.await("System cancellation rolls back") { h.controller.turn == null }
            assertEquals(0.0, h.js("estanteProgressValue").toDouble(), .001)
            assertEquals(0, h.js("Math.round(document.getElementById('estante-pages').scrollLeft)").toInt())
            h.quickSwipe()
            h.await("A fast swipe must be retained while the destination renders") { h.controller.turn?.after != null }
            h.await("Fast swipe completes") { h.controller.turn == null }
            assertEquals(1, h.js("Math.round(document.getElementById('estante-pages').scrollLeft/estantePageWidth)").toInt())
        }
    }

    @Test fun textSelectionAndLongPressAreNotPageTurnGestures() {
        ReaderHarness(curl = true).use { h ->
            h.load(SAMPLE); h.style(24f, false)
            h.js("estanteRestoreProgress(0);var r=document.createRange();r.selectNodeContents(document.querySelector('p.prose'));var s=getSelection();s.removeAllRanges();s.addRange(r)")
            h.pointer(android.view.MotionEvent.ACTION_DOWN, .9f, .75f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .8f, .7f)
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .65f, .65f)
            h.settle()
            assertNull("Existing selection must keep the gesture in WebView", h.controller.turn)
            h.pointer(android.view.MotionEvent.ACTION_UP, .65f, .65f)
            h.js("getSelection().removeAllRanges()")
            h.pointer(android.view.MotionEvent.ACTION_DOWN, .9f, .75f)
            repeat(4) { h.settle() }
            h.pointer(android.view.MotionEvent.ACTION_MOVE, .65f, .65f)
            assertNull("Long press must remain available for highlighting", h.controller.turn)
            h.pointer(android.view.MotionEvent.ACTION_UP, .65f, .65f)
            assertEquals(0.0, h.js("estanteProgressValue").toDouble(), .001)
        }
    }

    @Test fun suppliedEpubImportsAndRendersWithReadableTypography() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "reader-regression.epub")
        var copiedFixtureFromTestAssets = false
        if (!source.isFile) {
            copiedFixtureFromTestAssets = runCatching {
                InstrumentationRegistry.getInstrumentation().context.assets.open("reader-regression.epub").use { input ->
                    source.outputStream().use(input::copyTo)
                }
                true
            }.getOrDefault(false)
        }
        assumeTrue("Optional EPUB fixture must be staged in app files or test assets", source.isFile)
        val root = File(context.cacheDir, "reader-regression-import")
        try {
            val book = EpubImporter().import(source, root, "Regression book")
            assertTrue(book.chapters.size > 10)
            val index = book.chapters.indexOfFirst { chapter ->
                val file = File(root, chapter.href)
                file.length() > 8000 && org.jsoup.Jsoup.parse(file.readText()).select("p").any { it.text().length > 180 }
            }
            assertTrue(index >= 0)
            val integrationBookId = "highlight-integration-${java.util.UUID.randomUUID()}"
            val managedFile = File(context.filesDir, "library/test-fixtures/$integrationBookId.epub")
            managedFile.parentFile?.mkdirs()
            source.copyTo(managedFile, overwrite = true)
            val database = ComicDatabase.getInstance(context)
            val highlightRepository = BookHighlightRepository(database.bookHighlightDao())
            runBlocking {
                database.comicDao().upsert(
                    ComicEntity(
                        id = integrationBookId,
                        contentHash = null,
                        title = book.title,
                        displayName = managedFile.name,
                        format = ComicFormat.EPUB,
                        managedPath = managedFile.absolutePath,
                        sourceUri = null,
                        pageCount = book.chapters.size,
                        coverPageIndex = 0,
                        coverPath = null,
                        importState = ComicImportState.READY,
                        importError = null,
                        addedAt = System.currentTimeMillis(),
                        lastOpenedAt = null,
                        lastPageIndex = 0,
                        lastRegionId = null,
                        completed = false,
                    ),
                )
            }
            try {
            for (double in listOf(false, true)) {
                ReaderHarness().use { h ->
                    val viewModel = androidx.lifecycle.ViewModelProvider(h.activity)[EstanteViewModel::class.java]
                    try {
                        viewModel.openComic(integrationBookId)
                        runBlocking {
                            withTimeout(60_000) {
                                viewModel.state.first {
                                    it.publicationId == integrationBookId && it.publicationChapters.isNotEmpty() &&
                                        (!double || it.publicationHighlights.isNotEmpty())
                                }
                            }
                        }
                    val saveResult = CompletableDeferred<BookHighlightSaveResult>()
                    h.showPublication(
                        book,
                        index,
                        if (double) 800 else 360,
                        double,
                        bookId = integrationBookId,
                        readHighlights = { viewModel.state.value.publicationHighlights },
                        onSaveSelection = { expectedBookId, href, text, start, end, prefix, suffix, result ->
                            viewModel.addBookHighlight(expectedBookId, href, text, start, end, prefix, suffix) { saved ->
                                saveResult.complete(saved)
                                result(saved)
                            }
                        },
                    )
                    val metrics = JSONObject(h.js("""(function(){var p=Array.from(document.querySelectorAll('p')).find(function(p){return p.textContent.length>180;});return {font:parseFloat(getComputedStyle(p).fontSize),line:parseFloat(getComputedStyle(p).lineHeight),pages:document.getElementById('estante-pages').scrollWidth/estantePageWidth,images:Array.from(document.images).every(function(i){return i.complete&&i.naturalWidth>0;})};})()"""))
                    assertEquals(28.0, metrics.getDouble("font"), 0.1)
                    assertEquals(28 * 1.72, metrics.getDouble("line"), 0.1)
                    assertTrue("Local images must load", metrics.getBoolean("images"))
                    assertTrue(metrics.getDouble("pages") > 1)
                    val selected = JSONObject(org.json.JSONTokener(h.selectLongParagraphText(if (double) 30 else 0)).nextValue().toString())
                    val staleBookResult = CompletableDeferred<BookHighlightSaveResult>()
                    viewModel.addBookHighlight(
                        "different-book",
                        book.chapters[index].href,
                        selected.getString("text"),
                        0,
                        selected.getString("text").length,
                        "",
                        "",
                    ) { staleBookResult.complete(it) }
                    assertEquals(BookHighlightSaveResult.INVALID_SELECTION, runBlocking { staleBookResult.await() })
                    val beforeHighlight = h.savedSelections.size
                    h.performHighlightAction()
                    h.await("real EPUB selection reaches the reader callback") { h.savedSelections.size == beforeHighlight + 1 }
                    val result = runBlocking { withTimeout(20_000) { saveResult.await() } }
                    assertEquals(BookHighlightSaveResult.SAVED, result)
                    val savedSelection = h.savedSelections.last()
                    assertEquals(selected.getString("text"), savedSelection[1])
                    assertEquals(selected.getString("text").length, (savedSelection[3] as Int) - (savedSelection[2] as Int))
                    assertEquals("true", h.js("getSelection().isCollapsed"))
                    val persisted = runBlocking {
                        withTimeout(20_000) {
                            highlightRepository.observeForBook(integrationBookId).first { it.size == (if (double) 2 else 1) }
                        }
                    }
                    assertEquals(if (double) 2 else 1, persisted.size)
                    assertEquals(selected.getString("text"), persisted.last().selectedText)
                    h.awaitJs("Array.from(document.querySelectorAll('[data-estante-highlight]')).some(function(mark){return mark.textContent===${org.json.JSONObject.quote(selected.getString("text"))};})")
                    h.js("estanteRestoreProgress(0)")
                    h.screenshot("epub-${if (double) "double" else "single"}.png")
                    h.tapNext()
                    h.settle()
                    h.settle()
                    assertTrue("Production bridge must advance a page", h.js("document.getElementById('estante-pages').scrollLeft").toDouble() > 0)
                    // Allow the curl to finish, then cross a real spine boundary.
                    h.awaitJs("!EstanteProgress.isTurning()")
                    h.awaitJs("getSelection().isCollapsed")
                    val savedProgress = h.js("estanteProgressValue").toDouble()
                    h.pointer(android.view.MotionEvent.ACTION_DOWN, .9f, .75f)
                    h.pointer(android.view.MotionEvent.ACTION_MOVE, .85f, .73f)
                    h.pointer(android.view.MotionEvent.ACTION_MOVE, .8f, .71f)
                    h.pointer(android.view.MotionEvent.ACTION_MOVE, .65f, .68f)
                    h.awaitJs("window.estantePreviewActive===true")
                    h.pointer(android.view.MotionEvent.ACTION_MOVE, .4f, .6f)
                    repeat(3) { h.settle() }
                    h.js("EstanteProgress.onProgress($index,1)")
                    h.settle()
                    assertEquals("A stale progress callback must not save a preview", savedProgress, h.publishedPosition().second.toDouble(), .001)
                    assertEquals(savedProgress, h.js("estanteProgressValue").toDouble(), .001)
                    h.deviceScreenshot("epub-drag-${if (double) "double" else "single"}.png")
                    h.pointer(android.view.MotionEvent.ACTION_MOVE, .88f, .75f)
                    h.settle(); h.settle()
                    h.pointer(android.view.MotionEvent.ACTION_UP, .9f, .75f)
                    h.awaitJs("window.estantePreviewActive===false")
                    h.awaitJs("!EstanteProgress.isTurning()")
                    assertEquals(savedProgress, h.js("estanteProgressValue").toDouble(), .001)
                    val oldPath = h.js("location.pathname")
                    h.js("estanteRestoreProgress(1)")
                    h.js("estanteTurnPage(1)")
                    var changed = false
                    for (attempt in 0..40) {
                        h.settle()
                        if (h.js("location.pathname") != oldPath && h.js("typeof estanteRestoreProgress") == "\"function\"") { changed = true; break }
                    }
                    assertTrue("Production reader must open the next chapter", changed)
                    h.awaitJs("!EstanteProgress.isTurning()")
                    assertEquals(0.0, h.js("estanteProgressValue").toDouble(), 0.01)
                    val nextPath = h.js("location.pathname")
                    h.pointer(android.view.MotionEvent.ACTION_DOWN, .1f, .75f)
                    h.pointer(android.view.MotionEvent.ACTION_MOVE, .15f, .73f)
                    h.pointer(android.view.MotionEvent.ACTION_MOVE, .2f, .71f)
                    h.pointer(android.view.MotionEvent.ACTION_MOVE, .35f, .68f)
                    for (attempt in 0..40) {
                        h.settle()
                        if (h.js("location.pathname") != nextPath) break
                    }
                    assertNotEquals("Backward drag previews previous chapter: ${h.js("JSON.stringify({turning:EstanteProgress.isTurning(),selected:!getSelection().isCollapsed,progress:estanteProgressValue})")}", nextPath, h.js("location.pathname"))
                    h.awaitJs("estanteProgressValue===1")
                    h.pointer(android.view.MotionEvent.ACTION_MOVE, .12f, .74f)
                    h.settle(); h.settle()
                    h.pointer(android.view.MotionEvent.ACTION_UP, .1f, .75f)
                    for (attempt in 0..40) {
                        h.settle()
                        if (h.js("location.pathname") == nextPath) break
                    }
                    assertEquals("Cancelled chapter drag restores original chapter", nextPath, h.js("location.pathname"))
                    h.awaitJs("!EstanteProgress.isTurning()")
                    assertEquals(0.0, h.js("estanteProgressValue").toDouble(), .001)
                    }
                    finally { viewModel.closeDocument() }
                }
            }
            } finally {
                val saved = runBlocking { highlightRepository.observeForBook(integrationBookId).first() }
                saved.forEach { highlight -> runBlocking { highlightRepository.delete(highlight.id, integrationBookId) } }
                runBlocking { database.comicDao().deleteById(integrationBookId) }
                managedFile.delete()
                File(context.filesDir, "library/publications/$integrationBookId").deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
            if (copiedFixtureFromTestAssets) source.delete()
        }
    }

    class ReaderHarness(private val curl: Boolean = false) : AutoCloseable {
        private val inst = InstrumentationRegistry.getInstrumentation()
        internal val activity = inst.startActivitySync(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        internal val controller = BookPageTurnController()
        internal val savedSelections = java.util.concurrent.CopyOnWriteArrayList<List<Any?>>()
        lateinit var view: WebView
        private var loaded = CountDownLatch(1)
        private var font = 24f
        private var double = false
        private var publicationPosition = 0 to 0f
        fun publishedPosition(): Pair<Int, Float> {
            var result = 0 to 0f
            inst.runOnMainSync { result = publicationPosition }
            return result
        }
        init {
            inst.runOnMainSync {
                view = ReaderWebView(activity).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(this@ReaderHarness, "EstanteProgress")
                    addJavascriptInterface(SelectionBridge(), "EstanteHighlights")
                    if (curl) {
                        val gestures = BookDragTouchHandler(controller, { true }, { { _: Float -> } })
                        setOnTouchListener { _, event -> gestures.onTouch(this, event, true) }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, url: String?) {
                            if (url.orEmpty().endsWith("next.html")) {
                                applyReaderStyle(ReaderWebStyle(font, double, ReaderCanvasBackground.PAPER, 1.72f, 26f), 1, emptyList(), null) {}
                                evaluateJavascript("estanteRestoreProgress(0)", null)
                            }
                            loaded.countDown()
                        }
                    }
                }
                if (curl) activity.setContent {
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                        BookPageTurnOverlay(controller, ReaderCanvasBackground.PAPER)
                    }
                } else activity.addContentView(view, android.view.ViewGroup.LayoutParams(800, 740))
            }
        }
        @JavascriptInterface fun onPagination(page: Int, total: Int) {}
        @JavascriptInterface fun onProgress(chapter: Int, progression: Float) {}
        @JavascriptInterface fun isTurning(): Boolean = controller.turn != null
        @JavascriptInterface fun onReady() { view.post { controller.chapterReady(view) } }
        @JavascriptInterface fun onTurnRequested(direction: Int) {
            view.post {
                if (curl) controller.request(view, direction, true)
                else view.evaluateJavascript("estanteCommitTurnPage($direction)", null)
            }
        }
        @JavascriptInterface fun onChapterBoundary(direction: Int) {
            view.post { view.loadDataWithBaseURL("https://reader.estante.local/book/test/next.html", SAMPLE.replace("Long paragraph", "Next chapter"), "text/html", "UTF-8", "https://reader.estante.local/book/test/next.html") }
        }
        private inner class SelectionBridge {
            @JavascriptInterface fun onTextSelection(requestId: String, href: String, text: String, start: Int, end: Int, prefix: String, suffix: String) {
                savedSelections.add(listOf(href, text, start, end, prefix, suffix))
                view.post { (view as ReaderWebView).completeHighlightRequest(requestId, BookHighlightSaveResult.SAVED) }
            }
            @JavascriptInterface fun onHighlightVisible(id: String) {}
            @JavascriptInterface fun onSelectionHasHighlight(hasHighlight: Boolean) { view.post { (view as ReaderWebView).setSelectionHasHighlight(hasHighlight) } }
            @JavascriptInterface fun onRemoveHighlight(id: String) {}
            @JavascriptInterface fun onChangeHighlightColor(id: String, color: String) {}
            @JavascriptInterface fun onCopyText(text: String) {}
        }
        fun showPublication(
            book: com.estante.reader.core.publication.ImportedPublication,
            initialChapter: Int,
            width: Int,
            double: Boolean,
            bookId: String = "regression",
            readHighlights: () -> List<BookHighlightEntity> = { emptyList() },
            onSaveSelection: HighlightSaveCallback? = null,
        ) {
            var chapter by mutableStateOf(initialChapter)
            var progress by mutableStateOf(0f)
            var displayedHighlights by mutableStateOf(readHighlights())
            inst.runOnMainSync {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                activity.setContent {
                    Box(Modifier.width(width.dp).height(740.dp)) {
                        BookReaderScreen(
                            title = book.title, author = book.author, bookId = bookId,
                            rootPath = book.root.path, chapters = book.chapters,
                            chapterIndex = chapter, progression = progress,
                            fontSizeSp = 28f, lineSpacing = 1.72f, marginsDp = 26f,
                            columnMode = if (double) com.estante.reader.core.publication.BookColumnMode.DOUBLE else com.estante.reader.core.publication.BookColumnMode.SINGLE,
                            background = ReaderCanvasBackground.PAPER, capabilities = book.capabilities,
                            bookmarks = emptyList(), highlights = displayedHighlights, highlightColorId = "yellow",
                            pendingHighlight = null, searchResults = emptyList(), isSearching = false,
                            onClose = {}, onProgress = { c, p -> chapter = c; progress = p; publicationPosition = c to p },
                            onFontSizeChange = {}, onLineSpacingChange = {}, onMarginsChange = {},
                            onColumnsChange = {}, onBackgroundChange = {}, onAddBookmark = {},
                            onTextSelection = { expectedBookId, href, text, start, end, prefix, suffix, result ->
                                savedSelections.add(listOf(href, text, start, end, prefix, suffix))
                                val finish: (BookHighlightSaveResult) -> Unit = { outcome ->
                                    if (outcome == BookHighlightSaveResult.SAVED || outcome == BookHighlightSaveResult.ALREADY_EXISTS) {
                                        displayedHighlights = readHighlights()
                                    }
                                    result(outcome)
                                }
                                if (onSaveSelection == null) finish(BookHighlightSaveResult.SAVED)
                                else onSaveSelection.invoke(expectedBookId, href, text, start, end, prefix, suffix, finish)
                            }, onHighlightColorChange = {},
                            onHighlightItemColorChange = { _, _ -> }, onOpenHighlight = {},
                            onRemoveHighlight = {}, onRestoreHighlight = {}, onPendingHighlightConsumed = {},
                            onSearch = {}, onOpenSearchResult = {},
                        )
                    }
                }
            }
            fun find(v: android.view.View): WebView? {
                if (v is WebView) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            await("Production WebView appears") { find(activity.window.decorView)?.also { view = it } != null }
            for (attempt in 0..50) {
                if (js("typeof window.estanteTurnPage") == "\"function\"") { settle(); settle(); return }
                settle()
            }
            fail("Production chapter must load")
        }
        fun load(html: String) {
            loaded = CountDownLatch(1)
            inst.runOnMainSync { view.loadDataWithBaseURL("https://reader.estante.local/book/test/chapter.html", html, "text/html", "UTF-8", "https://reader.estante.local/book/test/chapter.html") }
            assertTrue(loaded.await(20, TimeUnit.SECONDS)); settle()
        }
        fun resize(width: Int, height: Int) {
            val density = activity.resources.displayMetrics.density
            inst.runOnMainSync {
                view.layoutParams = view.layoutParams.apply { this.width = (width * density).toInt(); this.height = (height * density).toInt() }
                view.requestLayout()
            }
            settle()
        }
        fun style(font: Float, double: Boolean) {
            this.font = font; this.double = double
            inst.runOnMainSync { view.applyReaderStyle(ReaderWebStyle(font, double, ReaderCanvasBackground.PAPER, 1.72f, 26f), 0, emptyList(), null) {} }
            settle()
        }
        fun focusHighlight(highlight: com.estante.reader.core.data.BookHighlightEntity) {
            inst.runOnMainSync { view.applyReaderStyle(ReaderWebStyle(24f, false, ReaderCanvasBackground.PAPER, 1.72f, 26f), 0, listOf(highlight), highlight) {} }
            settle()
        }
        fun js(script: String): String {
            val done = CountDownLatch(1); var result = ""
            inst.runOnMainSync { view.evaluateJavascript(script) { result = it; done.countDown() } }
            assertTrue(done.await(10, TimeUnit.SECONDS)); return result
        }
        fun selectLongParagraphText(offset: Int): String = js("""(function(){var p=Array.from(document.querySelectorAll('p')).find(function(p){return p.textContent.trim().length>180;}),w=document.createTreeWalker(p,NodeFilter.SHOW_TEXT),nodes=[],n;while(n=w.nextNode())if(n.nodeValue.length)nodes.push(n);var skip=$offset,left=24,r=document.createRange(),startNode=null,startOffset=0,endNode=null,endOffset=0;for(var i=0;i<nodes.length&&left>0;i++){var node=nodes[i];if(!startNode&&skip>=node.nodeValue.length){skip-=node.nodeValue.length;continue;}var localStart=startNode?0:skip;if(!startNode){startNode=node;startOffset=localStart;skip=0;}var take=Math.min(left,node.nodeValue.length-localStart);endNode=node;endOffset=localStart+take;left-=take;}if(left)throw new Error('Fixture paragraph is too short');r.setStart(startNode,startOffset);r.setEnd(endNode,endOffset);getSelection().removeAllRanges();getSelection().addRange(r);return JSON.stringify({text:r.toString()});})()""")
        fun performHighlightAction() {
            inst.runOnMainSync {
                val callback = object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = true
                    override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = true
                    override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
                    override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                }
                val mode = (view as ReaderWebView).startActionMode(callback)
                    ?: throw AssertionError("Reader selection menu must start")
                check(mode.menu.performIdentifierAction(7102, 0)) { "Grifar seleção action must be handled" }
            }
        }
        fun awaitJs(expression: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                if (js(expression) == "true") return
                Thread.sleep(40)
            }
            fail("JavaScript condition not reached: $expression; ${js("JSON.stringify({selection:!getSelection().isCollapsed,turning:EstanteProgress.isTurning(),preview:window.estantePreviewActive})")}")
        }
        fun settle() {
            val done = CountDownLatch(1)
            inst.runOnMainSync { view.postDelayed({ done.countDown() }, 200) }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                var ok = false
                inst.runOnMainSync { ok = predicate() }
                if (ok) return
                Thread.sleep(16)
            }
            fail("$message: state=${js("JSON.stringify({ready:typeof estanteRestoreProgress,progress:window.estanteProgressValue})")}")
        }
        private var pointerDownTime = 0L
        fun pointer(action: Int, xFraction: Float, yFraction: Float) {
            inst.runOnMainSync {
                val now = android.os.SystemClock.uptimeMillis()
                if (action == android.view.MotionEvent.ACTION_DOWN) pointerDownTime = now
                val event = android.view.MotionEvent.obtain(
                    pointerDownTime, now, action,
                    view.width * xFraction, view.height * yFraction, 0,
                ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            Thread.sleep(40)
        }
        fun quickSwipe() {
            inst.runOnMainSync {
                val time = android.os.SystemClock.uptimeMillis()
                listOf(
                    Triple(android.view.MotionEvent.ACTION_DOWN, .9f, .75f),
                    Triple(android.view.MotionEvent.ACTION_MOVE, .65f, .65f),
                    Triple(android.view.MotionEvent.ACTION_MOVE, .1f, .55f),
                    Triple(android.view.MotionEvent.ACTION_UP, .1f, .55f),
                ).forEachIndexed { index, (action, x, y) ->
                    val event = android.view.MotionEvent.obtain(time, time + index * 20, action, view.width * x, view.height * y, 0)
                        .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
        }
        fun tapNext() {
            val location = IntArray(2)
            var x = 0f; var y = 0f
            inst.runOnMainSync {
                view.getLocationOnScreen(location)
                x = location[0] + view.width * .9f
                y = location[1] + view.height * .5f
            }
            val result = inst.uiAutomation.executeShellCommand("input tap ${x.toInt()} ${y.toInt()}")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
        }
        fun deviceScreenshot(name: String) {
            val bitmap = inst.uiAutomation.takeScreenshot()
            val location = IntArray(2)
            inst.runOnMainSync { view.getLocationOnScreen(location) }
            val pixel = bitmap.getPixel(location[0] + 12, location[1] + 12)
            assertTrue("Reader must be visible in the actual window", android.graphics.Color.red(pixel) < 250 || android.graphics.Color.green(pixel) < 250 || android.graphics.Color.blue(pixel) < 250)
            File(inst.targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun screenshot(name: String) {
            inst.runOnMainSync {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(inst.targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        override fun close() {
            inst.runOnMainSync { controller.cancel(); activity.finish(); view.destroy() } }
    }

    companion object {
        private val SAMPLE = """<!doctype html><html lang="pt-BR"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>p{font-size:.833em;line-height:1.5;text-align:justify}p.title{font-size:8em;line-height:1}p span{font-size:.7em}</style></head><body><p class="title">Readable title</p><p class="prose">""" + (1..180).joinToString(" ") { "Long paragraph $it with <span>nested text</span> that should reflow into readable whole lines across many pages." } + "</p><p>Last paragraph.</p></body></html>"
    }
}
