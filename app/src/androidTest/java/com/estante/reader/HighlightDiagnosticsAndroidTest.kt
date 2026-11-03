package com.estante.reader

import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.estante.reader.core.data.*

/** Regression coverage for explicit selection actions and text anchors. */
class HighlightDiagnosticsAndroidTest {
    private val html = "<html><body><p>Primeiro trecho para selecionar e continuar ajustando com calma.</p></body></html>"
    private val select = """var n=document.querySelector('p').firstChild,r=document.createRange();r.setStart(n,0);r.setEnd(n,15);getSelection().removeAllRanges();getSelection().addRange(r);"""

    private fun prepare(h: BookReaderRegressionAndroidTest.ReaderHarness) {
        h.load(html)
        h.style(24f, false)
        h.js("window.saved=[];window.EstanteHighlights={onTextSelection:function(){saved.push(Array.from(arguments));},onHighlightVisible:function(){},onSelectionHasHighlight:function(){},onRemoveHighlight:function(id){window.removed=id},onChangeHighlightColor:function(id,color){window.changed=[id,color]},onCopyText:function(text){window.copied=text}}")
    }

    @Test fun selectionStaysIntactUntilUserConfirmsGriffing() {
        BookReaderRegressionAndroidTest.ReaderHarness().use { h ->
            prepare(h)
            h.js(select)
            repeat(8) { h.settle() }
            assertEquals("0", h.js("saved.length"))
            assertEquals("Primeiro trecho", org.json.JSONTokener(h.js("String(getSelection())")).nextValue().toString())
            h.js("estanteConfirmHighlight()")
            assertEquals("1", h.js("saved.length"))
            assertEquals("Primeiro trecho", org.json.JSONTokener(h.js("saved[0][1]")).nextValue().toString())
            assertEquals("true", h.js("getSelection().isCollapsed"))
        }
    }

    @Test fun sameRangeCanBeConfirmedAgainAfterRemoval() {
        BookReaderRegressionAndroidTest.ReaderHarness().use { h ->
            prepare(h)
            h.js(select)
            h.js("estanteConfirmHighlight()")
            h.js("estanteSelectionSnapshot=null")
            h.js(select)
            h.js("estanteConfirmHighlight()")
            assertEquals("2", h.js("saved.length"))
        }
    }

    @Test fun textOffsetsExcludeEmbeddedStylesAndRestoreTheExactQuote() {
        BookReaderRegressionAndroidTest.ReaderHarness().use { h ->
            h.load("<html><body><style>p{color:black}</style><p>Primeiro trecho para selecionar.</p></body></html>")
            h.style(24f, false)
            h.js("window.saved=[];window.EstanteHighlights={onTextSelection:function(){saved.push(Array.from(arguments));},onHighlightVisible:function(){},onSelectionHasHighlight:function(){},onRemoveHighlight:function(id){window.removed=id},onChangeHighlightColor:function(id,color){window.changed=[id,color]},onCopyText:function(){}}")
            h.js(select)
            h.js("estanteConfirmHighlight()")
            assertEquals("0", h.js("saved[0][2]"))
            val start = h.js("saved[0][2]").toInt()
            val end = h.js("saved[0][3]").toInt()
            val quote = org.json.JSONTokener(h.js("saved[0][1]")).nextValue().toString()
            val prefix = org.json.JSONTokener(h.js("JSON.stringify(saved[0][4])")).nextValue().toString()
            val suffix = org.json.JSONTokener(h.js("JSON.stringify(saved[0][5])")).nextValue().toString()
            val entity = com.estante.reader.core.data.BookHighlightEntity("diagnostic", "test", 0, "chapter.html", start, end, quote, prefix, suffix, "yellow", 0L)
            h.js("window.readerHighlightError=null;window.onerror=function(m){window.readerHighlightError=String(m)}")
            h.focusHighlight(entity)
            val highlighted = org.json.JSONTokener(h.js("document.querySelector('[data-estante-highlight]')?.textContent || ''")).nextValue().toString()
            assertEquals("anchor=${h.js("JSON.stringify({entity:'$quote',start:$start,end:$end,quote:saved[0][1],actual:(function(){var w=document.createTreeWalker(document.getElementById('estante-content'),NodeFilter.SHOW_TEXT),a='',n;while(n=w.nextNode())if(!n.parentElement.closest('style,script'))a+=n.nodeValue;return a.slice(0,30)})(),error:readerHighlightError})")}", "Primeiro trecho", highlighted)
            h.js("var mark=document.querySelector('[data-estante-highlight]'),range=document.createRange();range.selectNodeContents(mark);getSelection().removeAllRanges();getSelection().addRange(range)")
            h.settle()
            assertEquals("diagnostic", org.json.JSONTokener(h.js("estanteSelectionSnapshot.mark")).nextValue().toString())
            assertEquals("diagnostic", org.json.JSONTokener(h.js("estanteChangeSelectedHighlightColor('green')")).nextValue().toString())
            assertEquals("green", org.json.JSONTokener(h.js("window.changed && window.changed[1]")).nextValue().toString())
            h.js("var mark=document.querySelector('[data-estante-highlight]'),range=document.createRange();range.selectNodeContents(mark);getSelection().removeAllRanges();getSelection().addRange(range)")
            h.settle()
            assertEquals("diagnostic", org.json.JSONTokener(h.js("estanteRemoveSelectedHighlight()")).nextValue().toString())
            assertEquals("diagnostic", org.json.JSONTokener(h.js("removed")).nextValue().toString())
        }
    }

    @Test fun nativeLongPressUsesReaderActionsAndDoesNotSaveUntilConfirmed() {
        BookReaderRegressionAndroidTest.ReaderHarness().use { h ->
            prepare(h)
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.runOnMainSync {
                (h.view.parent as android.view.ViewGroup).removeView(h.view)
                (h.view.context as MainActivity).setContentView(h.view)
                h.view.requestFocus()
            }
            h.settle()
            val started = java.util.concurrent.CountDownLatch(1)
            val menu = java.util.concurrent.CopyOnWriteArrayList<String>()
            val activity = h.view.context as MainActivity
            inst.runOnMainSync {
                val original = activity.window.callback
                activity.window.callback = object : android.view.Window.Callback by original {
                    override fun onActionModeStarted(mode: android.view.ActionMode) {
                        original.onActionModeStarted(mode)
                        for (i in 0 until mode.menu.size()) menu.add(mode.menu.getItem(i).title.toString())
                        started.countDown()
                    }
                }
            }
            val point = org.json.JSONObject(h.js("""(function(){var r=document.createRange();r.setStart(document.querySelector('p').firstChild,0);r.setEnd(document.querySelector('p').firstChild,8);var b=r.getBoundingClientRect();return {x:(b.left+b.right)/2/innerWidth,y:(b.top+b.bottom)/2/innerHeight};})()"""))
            val location = IntArray(2)
            var x = 0f; var y = 0f
            inst.runOnMainSync {
                h.view.getLocationOnScreen(location)
                x = location[0] + h.view.width * point.getDouble("x").toFloat()
                y = location[1] + h.view.height * point.getDouble("y").toFloat()
            }
            val down = android.os.SystemClock.uptimeMillis()
            fun send(action: Int) {
                val event = MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { inst.sendPointerSync(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN)
            val opened = started.await(5, java.util.concurrent.TimeUnit.SECONDS)
            h.deviceScreenshot("highlight-native-diagnostic.png")
            assertTrue("Native ActionMode must start", opened)
            assertTrue("Android offers native text actions", menu.isNotEmpty())
            assertTrue(menu.contains("Grifar seleção"))
            assertFalse(menu.any { it in listOf("Translate", "Share", "Select all", "Read aloud") })
            h.js(select)
            repeat(8) { h.settle() }
            assertEquals("0", h.js("saved.length"))
            h.js("estanteConfirmHighlight()")
            assertEquals("1", h.js("saved.length"))
            send(MotionEvent.ACTION_UP)
        }
    }

    @Test fun removalIsScopedToBookAndCanBeRestoredInStorage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, ComicDatabase::class.java).build()
        try {
            for (id in listOf("book-a", "book-b")) db.comicDao().upsert(ComicEntity(
                id, null, id, id, ComicFormat.EPUB, null, null, 1, 0, null,
                ComicImportState.READY, null, 0L, null, 0, null, false,
            ))
            val repository = BookHighlightRepository(db.bookHighlightDao())
            val mark = BookHighlightEntity("mark-a", "book-a", 0, "chapter.html", 0, 15, "Primeiro trecho", "", "", "yellow", 0L)
            repository.insert(mark)
            assertEquals(0, repository.delete(mark.id, "book-b"))
            assertEquals(listOf(mark), repository.observeForBook("book-a").first())
            assertEquals(1, repository.delete(mark.id, "book-a"))
            assertTrue(repository.observeForBook("book-a").first().isEmpty())
            repository.insert(mark)
            assertEquals(listOf(mark), repository.observeForBook("book-a").first())
        } finally { db.close() }
    }
}
