package com.estante.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReaderPaginationAndroidTest {
    @Test fun pagesMoveHorizontallyInSingleAndDoubleColumnLayouts() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val activity = inst.startActivitySync(android.content.Intent(inst.targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val loaded = CountDownLatch(1)
        lateinit var view: WebView
        inst.runOnMainSync {
            view = WebView(inst.targetContext).apply {
                settings.javaScriptEnabled = true
                measure(android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
                activity.addContentView(this, android.view.ViewGroup.LayoutParams(800, 600))
                layout(0, 0, 800, 600)
                webViewClient = object : WebViewClient() { override fun onPageFinished(v: WebView?, url: String?) { loaded.countDown() } }
                loadDataWithBaseURL("https://reader.estante.local/book/test/chapter.html", "<!doctype html><html><body>" + (1..120).joinToString("") { "<p>Paragraph $it. This is a long chapter for horizontal pagination and readable book layouts.</p>" } + "</body></html>", "text/html", "UTF-8", null)
            }
        }
        fun evaluate(script: String): String {
            val done = CountDownLatch(1); var result = ""
            inst.runOnMainSync { view.evaluateJavascript(script) { result = it; done.countDown() } }
            assertTrue(done.await(10, TimeUnit.SECONDS)); return result
        }
        fun settle() { val done=CountDownLatch(1); inst.runOnMainSync { view.postDelayed({ done.countDown() }, 600) }; assertTrue(done.await(5,TimeUnit.SECONDS)) }
        try {
            assertTrue(loaded.await(20, TimeUnit.SECONDS))
            evaluate("window.EstanteProgress={onPagination:function(){},onProgress:function(){},onChapterBoundary:function(d){window.boundary=d;}}")
            for (double in listOf(false, true)) {
                inst.runOnMainSync { view.applyReaderStyle(ReaderWebStyle(20f, double, ReaderCanvasBackground.PAPER, 1.72f, 26f), 0, emptyList(), null) {} }
                settle()
                evaluate("window.estanteRestoreProgress(0)")
                val before = JSONObject(evaluate("({width:window.estantePageWidth,extent:document.getElementById('estante-pages').scrollWidth,x:document.getElementById('estante-pages').scrollLeft,y:scrollY})"))
                assertTrue("Chapter must span multiple pages", before.getInt("extent") > before.getInt("width"))
                evaluate("window.estanteTurnPage(1)"); settle()
                val after = JSONObject(evaluate("({x:document.getElementById('estante-pages').scrollLeft,y:scrollY})"))
                assertTrue("Page must advance horizontally", after.getDouble("x") > 0)
                assertEquals(0,after.getInt("y"))
                evaluate("window.estanteTurnPage(-1)"); settle()
                assertEquals(0, evaluate("Math.round(document.getElementById('estante-pages').scrollLeft)").toInt())
                evaluate("window.estanteRestoreProgress(1)"); settle()
                evaluate("window.estanteTurnPage(1)")
                assertEquals(evaluate("JSON.stringify({x:document.getElementById('estante-pages').scrollLeft,w:window.estantePageWidth,body:document.body.scrollWidth,root:document.getElementById('estante-pages').scrollWidth,mode:document.compatMode})"),"1",evaluate("window.boundary"))
            }
        } finally { inst.runOnMainSync { view.destroy(); activity.finish() } }
    }
}
