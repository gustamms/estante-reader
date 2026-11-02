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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

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

    @Test fun suppliedEpubImportsAndRendersWithReadableTypography() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "reader-regression.epub")
        assumeTrue("Optional local EPUB fixture must be staged in app files", source.isFile)
        val root = File(context.cacheDir, "reader-regression-import")
        try {
            val book = EpubImporter().import(source, root, "Regression book")
            assertTrue(book.chapters.size > 10)
            val index = book.chapters.indexOfFirst { chapter ->
                val file = File(root, chapter.href)
                file.length() > 8000 && org.jsoup.Jsoup.parse(file.readText()).select("p").any { it.text().length > 180 }
            }
            assertTrue(index >= 0)
            for (double in listOf(false, true)) {
                ReaderHarness().use { h ->
                    h.showPublication(book, index, if (double) 800 else 360, double)
                    val metrics = JSONObject(h.js("""(function(){var p=Array.from(document.querySelectorAll('p')).find(function(p){return p.textContent.length>180;});return {font:parseFloat(getComputedStyle(p).fontSize),line:parseFloat(getComputedStyle(p).lineHeight),pages:document.getElementById('estante-pages').scrollWidth/estantePageWidth,images:Array.from(document.images).every(function(i){return i.complete&&i.naturalWidth>0;})};})()"""))
                    assertEquals(28.0, metrics.getDouble("font"), 0.1)
                    assertEquals(28 * 1.72, metrics.getDouble("line"), 0.1)
                    assertTrue("Local images must load", metrics.getBoolean("images"))
                    assertTrue(metrics.getDouble("pages") > 1)
                    h.js("estanteRestoreProgress(0)")
                    h.screenshot("epub-${if (double) "double" else "single"}.png")
                    h.tapNext()
                    h.settle()
                    h.settle()
                    assertTrue("Production bridge must advance a page", h.js("document.getElementById('estante-pages').scrollLeft").toDouble() > 0)
                    // Allow the curl to finish, then cross a real spine boundary.
                    repeat(5) { h.settle() }
                    val oldPath = h.js("location.pathname")
                    h.js("estanteRestoreProgress(1)")
                    h.js("estanteTurnPage(1)")
                    var changed = false
                    for (attempt in 0..40) {
                        h.settle()
                        if (h.js("location.pathname") != oldPath && h.js("typeof estanteRestoreProgress") == "\"function\"") { changed = true; break }
                    }
                    assertTrue("Production reader must open the next chapter", changed)
                    repeat(5) { h.settle() }
                    assertEquals(0.0, h.js("estanteProgressValue").toDouble(), 0.01)
                }
            }
        } finally { root.deleteRecursively() }
    }

    class ReaderHarness(private val curl: Boolean = false) : AutoCloseable {
        private val inst = InstrumentationRegistry.getInstrumentation()
        private val activity = inst.startActivitySync(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        internal val controller = BookPageTurnController()
        lateinit var view: WebView
        private var loaded = CountDownLatch(1)
        private var font = 24f
        private var double = false
        init {
            inst.runOnMainSync {
                view = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(this@ReaderHarness, "EstanteProgress")
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
        fun showPublication(book: com.estante.reader.core.publication.ImportedPublication, initialChapter: Int, width: Int, double: Boolean) {
            var chapter by mutableStateOf(initialChapter)
            var progress by mutableStateOf(0f)
            inst.runOnMainSync {
                view.destroy()
                activity.setContent {
                    Box(Modifier.width(width.dp).height(740.dp)) {
                        BookReaderScreen(
                            title = book.title, author = book.author, bookId = "regression",
                            rootPath = book.root.path, chapters = book.chapters,
                            chapterIndex = chapter, progression = progress,
                            fontSizeSp = 28f, lineSpacing = 1.72f, marginsDp = 26f,
                            columnMode = if (double) com.estante.reader.core.publication.BookColumnMode.DOUBLE else com.estante.reader.core.publication.BookColumnMode.SINGLE,
                            background = ReaderCanvasBackground.PAPER, capabilities = book.capabilities,
                            bookmarks = emptyList(), highlights = emptyList(), highlightColorId = "yellow",
                            pendingHighlight = null, searchResults = emptyList(), isSearching = false,
                            onClose = {}, onProgress = { c, p -> chapter = c; progress = p },
                            onFontSizeChange = {}, onLineSpacingChange = {}, onMarginsChange = {},
                            onColumnsChange = {}, onBackgroundChange = {}, onAddBookmark = {},
                            onTextSelection = { _, _, _, _, _, _ -> }, onHighlightColorChange = {},
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
        fun tapNext() {
            val location = IntArray(2)
            var x = 0f; var y = 0f
            inst.runOnMainSync {
                view.getLocationOnScreen(location)
                x = location[0] + view.width * .9f
                y = location[1] + view.height * .5f
            }
            val time = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            val up = android.view.MotionEvent.obtain(time, time + 50, android.view.MotionEvent.ACTION_UP, x, y, 0)
            try { inst.sendPointerSync(down); Thread.sleep(50); inst.sendPointerSync(up) }
            finally { down.recycle(); up.recycle() }
        }
        fun deviceScreenshot(name: String) {
            val bitmap = inst.uiAutomation.takeScreenshot()
            File(inst.targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun screenshot(name: String) {
            inst.runOnMainSync {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(inst.targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        override fun close() { inst.runOnMainSync { controller.cancel(); activity.finish(); view.destroy() } }
    }

    companion object {
        private val SAMPLE = """<!doctype html><html lang="pt-BR"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>p{font-size:.833em;line-height:1.5;text-align:justify}p.title{font-size:8em;line-height:1}p span{font-size:.7em}</style></head><body><p class="title">Readable title</p><p class="prose">""" + (1..180).joinToString(" ") { "Long paragraph $it with <span>nested text</span> that should reflow into readable whole lines across many pages." } + "</p><p>Last paragraph.</p></body></html>"
    }
}
