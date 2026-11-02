package com.estante.reader

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.rememberPageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.PageCurlState

/** Keep the live, selectable WebView underneath; curl only the two visible page images. */
@OptIn(ExperimentalPageCurlApi::class)
internal class BookPageTurnController {
    internal data class Turn(
        val before: Bitmap,
        val after: Bitmap?,
        val direction: Int,
        val animation: PageCurlState = PageCurlState(initialMax = 2, initialCurrent = if (direction > 0) 0 else 1),
    )

    var turn by mutableStateOf<Turn?>(null)
        private set
    private var sourceUrl: String? = null
    private var waitingForChapter = false
    private var generation = 0L

    fun request(view: WebView, direction: Int, canChangeChapter: Boolean) {
        if (turn != null || direction !in listOf(-1, 1) || view.width == 0 || view.height == 0) return
        val token = ++generation
        sourceUrl = view.url
        turn = Turn(capture(view), null, direction)
        // Prevent a failed chapter load from leaving the reader covered indefinitely.
        view.postDelayed({ if (generation == token && turn?.after == null) cancel() }, 10_000)
        view.evaluateJavascript("window.estanteCommitTurnPage?.($direction)") { result ->
            if (generation != token) return@evaluateJavascript
            when (result) {
                "\"turned\"" -> captureDestination(view, token)
                "\"boundary\"" -> {
                    if (canChangeChapter) {
                        waitingForChapter = true
                        view.evaluateJavascript("EstanteProgress.onChapterBoundary($direction)", null)
                    } else cancel()
                }
                else -> cancel()
            }
        }
    }

    fun chapterReady(view: WebView) {
        if (waitingForChapter && view.url != sourceUrl) {
            waitingForChapter = false
            captureDestination(view, generation)
        }
    }

    private fun captureDestination(view: WebView, token: Long) {
        // evaluateJavascript completes before WebView's renderer presents the new page.
        view.postVisualStateCallback(token, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                view.postOnAnimation {
                    if (generation != token) return@postOnAnimation
                    val current = turn ?: return@postOnAnimation
                    if (view.width != current.before.width || view.height != current.before.height) {
                        cancel()
                    } else if (!ValueAnimator.areAnimatorsEnabled()) {
                        cancel()
                    } else {
                        turn = current.copy(after = capture(view))
                    }
                }
            }
        })
    }

    fun finish(completed: Turn) {
        if (turn === completed) cancel()
    }

    fun cancel() {
        generation++
        waitingForChapter = false
        turn = null
    }

    private fun capture(view: WebView): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
}

@OptIn(ExperimentalPageCurlApi::class)
@Composable
internal fun BookPageTurnOverlay(controller: BookPageTurnController, background: ReaderCanvasBackground, modifier: Modifier = Modifier) {
    val turn = controller.turn ?: return
    val after = turn.after
    if (after == null) {
        Image(turn.before.asImageBitmap(), null, modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        return
    }
    val forward = turn.direction > 0
    val state = turn.animation
    val config = rememberPageCurlConfig(
        backPageColor = background.color,
        backPageContentAlpha = 0.08f,
        shadowAlpha = 0.28f,
        shadowRadius = 18.dp,
        dragForwardEnabled = false,
        dragBackwardEnabled = false,
        tapForwardEnabled = false,
        tapBackwardEnabled = false,
        tapCustomEnabled = false,
    )
    PageCurl(count = 2, state = state, config = config, modifier = modifier.fillMaxSize()) { page ->
        val bitmap = if ((page == 0) == forward) turn.before else after
        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().background(background.color), contentScale = ContentScale.FillBounds)
    }
    LaunchedEffect(turn) {
        try {
            // PageCurl initializes its animation edges during its first layout.
            // Starting before that layout silently skips the library's animation.
            withFrameNanos { }
            withFrameNanos { }
            if (forward) state.next() else state.prev()
        } finally {
            controller.finish(turn)
        }
    }
}
