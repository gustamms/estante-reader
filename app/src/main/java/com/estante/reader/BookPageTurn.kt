package com.estante.reader

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.PageCurlState
import com.estante.reader.core.guided.ReadingDirection
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import kotlin.math.abs

/** The live WebView remains selectable; PageCurl receives the actual pointer stream. */
@OptIn(ExperimentalPageCurlApi::class)
internal class BookPageTurnController {
    internal data class Turn(
        val before: Bitmap,
        val after: Bitmap?,
        val direction: Int,
        val drag: BookDragSession? = null,
        val animation: PageCurlState = PageCurlState(
            initialMax = if (drag == null) 2 else 3,
            initialCurrent = if (drag != null) 1 else if (direction > 0) 0 else 1,
        ),
    )

    var turn by mutableStateOf<Turn?>(null)
        private set
    private var sourceUrl: String? = null
    private var sourceProgress = 0f
    private var sourceView: WebView? = null
    private var restoreChapter: ((Float) -> Unit)? = null
    private var waitingForChapter = false
    private var rollingBack = false
    private var generation = 0L

    fun request(
        view: WebView,
        direction: Int,
        canChangeChapter: Boolean,
        drag: BookDragSession? = null,
        onRestoreChapter: ((Float) -> Unit)? = null,
    ): Boolean {
        if (turn != null || direction !in listOf(-1, 1) || view.width == 0 || view.height == 0) return false
        val token = ++generation
        sourceUrl = view.url
        sourceView = view
        restoreChapter = onRestoreChapter
        sourceProgress = 0f
        turn = Turn(capture(view), null, direction, drag)
        view.postDelayed({ if (generation == token && turn?.after == null) cancel() }, 10_000)
        // A drag previews a page without publishing progress until the plugin commits it.
        view.evaluateJavascript("JSON.stringify({progress:window.estanteProgressValue||0,result:window.estanteCommitTurnPage?.($direction,${drag != null})})") { result ->
            if (generation != token || rollingBack) return@evaluateJavascript
            val data = runCatching { JSONObject(org.json.JSONTokener(result).nextValue().toString()) }.getOrNull()
            sourceProgress = data?.optDouble("progress", 0.0)?.toFloat() ?: 0f
            when (data?.optString("result")) {
                "turned" -> captureDestination(view, token)
                "boundary" -> {
                    if (canChangeChapter) {
                        waitingForChapter = true
                        view.evaluateJavascript("EstanteProgress.onChapterBoundary($direction)", null)
                    } else turn?.let { finish(it) }
                }
                else -> clear()
            }
        }
        return true
    }

    fun chapterReady(view: WebView) {
        if (rollingBack && view.url == sourceUrl) {
            revealLivePage(view, generation)
        } else if (waitingForChapter && view.url != sourceUrl) {
            waitingForChapter = false
            captureDestination(view, generation)
        }
    }

    private fun captureDestination(view: WebView, token: Long) {
        view.postVisualStateCallback(token, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                view.postOnAnimation {
                    if (generation != token || rollingBack) return@postOnAnimation
                    val current = turn ?: return@postOnAnimation
                    if (view.width != current.before.width || view.height != current.before.height) {
                        cancel()
                    } else if (current.drag == null && !ValueAnimator.areAnimatorsEnabled()) {
                        finish(current)
                    } else {
                        turn = current.copy(after = capture(view))
                    }
                }
            }
        })
    }

    fun finish(completed: Turn, commit: Boolean = true) {
        if (turn !== completed) return
        if (!commit && completed.drag != null) {
            rollback()
        } else {
            sourceView?.evaluateJavascript("window.estanteConfirmTurnPage?.()", null)
            clear()
        }
    }

    fun cancel(restore: Boolean = true) {
        if (restore && turn?.drag != null) rollback() else clear()
    }

    private fun rollback() {
        if (rollingBack) return
        val view = sourceView ?: return clear()
        rollingBack = true
        waitingForChapter = false
        val token = generation
        // Keep the original page covering WebView until the rollback is rendered.
        turn?.drag?.close()
        turn = turn?.copy(after = null)
        if (view.url != sourceUrl && restoreChapter != null) {
            view.evaluateJavascript("window.estantePreviewActive=true", null)
            restoreChapter?.invoke(sourceProgress)
        } else {
            view.evaluateJavascript("window.estanteRestoreProgress?.($sourceProgress)") {
                if (generation == token) revealLivePage(view, token)
            }
        }
        view.postDelayed({ if (generation == token) clear() }, 10_000)
    }

    private fun revealLivePage(view: WebView, token: Long) {
        view.postVisualStateCallback(token, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { if (generation == token) clear() }
        })
    }

    private fun clear() {
        generation++
        turn?.drag?.close()
        waitingForChapter = false
        rollingBack = false
        turn = null
        sourceView = null
        restoreChapter = null
    }

    private fun capture(view: WebView): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
}

/** Buffer while WebView renders the destination, then forward native events to PageCurl. */
internal class BookDragSession(event: MotionEvent) {
    private val down = MotionEvent.obtain(event)
    private var move: MotionEvent? = null
    private var end: MotionEvent? = null
    private var surface: ComposeView? = null
    private var closed = false
    var released by mutableStateOf(false)
        private set

    fun dispatch(event: MotionEvent) {
        if (closed || released) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> { move?.recycle(); move = MotionEvent.obtain(event) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { end?.recycle(); end = MotionEvent.obtain(event) }
        }
        surface?.let { target ->
            target.dispatchTouchEvent(event)
            if (end != null) released = true
        }
    }

    fun bind(target: ComposeView) {
        if (closed || surface != null) return
        target.dispatchTouchEvent(down)
        // Give the plugin's pointer handler a frame between the buffered down/move/up.
        target.postOnAnimation {
            if (closed) return@postOnAnimation
            surface = target
            move?.let { target.dispatchTouchEvent(it) }
            target.postOnAnimation {
                if (!closed && !released) end?.let { target.dispatchTouchEvent(it); released = true }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        down.recycle(); move?.recycle(); end?.recycle()
        surface = null
    }
}

/** Finish WebView's native press when the reader consumes its tap or takes over its drag. */
internal fun WebView.cancelBookPointer(event: MotionEvent) {
    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
    try { onTouchEvent(cancel) } finally { cancel.recycle() }
}

/** Recognize a horizontal drag without taking long presses or existing selections away. */
internal class BookDragTouchHandler(
    private val controller: BookPageTurnController,
    private val canChangeChapter: (Int) -> Boolean,
    private val chapterRestorer: () -> (Float) -> Unit,
    private val readingDirection: () -> ReadingDirection = { ReadingDirection.LEFT_TO_RIGHT },
) {
    private var down: MotionEvent? = null
    private var selecting: Boolean? = null
    private var pendingMove: MotionEvent? = null
    private var pendingEnd: MotionEvent? = null

    fun onTouch(view: WebView, event: MotionEvent, enabled: Boolean): Boolean {
        controller.turn?.let { turn ->
            turn.drag?.dispatch(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) reset()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                if (!enabled || event.pointerCount != 1) return false
                down = MotionEvent.obtain(event)
                selecting = null
                val time = event.downTime
                view.evaluateJavascript("!!window.getSelection()?.isCollapsed") { value ->
                    if (down?.downTime == time) {
                        selecting = value != "true"
                        val pending = pendingMove
                        pendingMove = null
                        if (pending != null) try { onTouch(view, pending, enabled) } finally { pending.recycle() }
                        pendingEnd?.let { end -> controller.turn?.drag?.dispatch(end); reset() }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val start = down ?: return false
                if (selecting == null) {
                    pendingMove?.recycle()
                    pendingMove = MotionEvent.obtain(event)
                    return false
                }
                val dx = event.x - start.x
                val dy = event.y - start.y
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                if (!enabled || event.pointerCount != 1 || selecting == true || event.eventTime - start.eventTime >= ViewConfiguration.getLongPressTimeout()) return false
                val forwardAtStart = readingDirection() == ReadingDirection.RIGHT_TO_LEFT
                val startEdgeIsForward = if (forwardAtStart) start.x < view.width / 2f else start.x >= view.width / 2f
                val direction = if (startEdgeIsForward) 1 else -1
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f && dx * direction < 0) {
                    val drag = BookDragSession(start)
                    if (controller.request(view, direction, canChangeChapter(direction), drag, chapterRestorer())) {
                        // Cancel native selection/scrolling; the same pointer stream now goes to PageCurl.
                        view.cancelBookPointer(event)
                        drag.dispatch(event)
                        return true
                    }
                    drag.close()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (selecting == null && pendingMove != null) {
                    pendingEnd?.recycle()
                    pendingEnd = MotionEvent.obtain(event)
                } else reset()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return false
    }

    private fun reset() {
        down?.recycle(); down = null
        pendingMove?.recycle(); pendingMove = null
        pendingEnd?.recycle(); pendingEnd = null
    }
}

@OptIn(ExperimentalPageCurlApi::class)
@Composable
internal fun BookPageTurnOverlay(
    controller: BookPageTurnController,
    background: ReaderCanvasBackground,
    modifier: Modifier = Modifier,
    readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
) {
    val turn = controller.turn ?: return
    if (turn.after == null) {
        Image(turn.before.asImageBitmap(), null, modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    } else if (turn.drag == null) {
        BookCurl(controller, turn, background, modifier, readingDirection = readingDirection)
    } else {
        // A dedicated input surface lets WebView hand over the pointer that started on text.
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context -> ComposeView(context).apply {
                val surface = this
                setContent { BookCurl(controller, turn, background, Modifier, surface, readingDirection) }
            } },
            onRelease = { it.disposeComposition() },
        )
    }
}

@OptIn(ExperimentalPageCurlApi::class)
@Composable
private fun BookCurl(
    controller: BookPageTurnController,
    turn: BookPageTurnController.Turn,
    background: ReaderCanvasBackground,
    modifier: Modifier,
    surface: ComposeView? = null,
    readingDirection: ReadingDirection,
) {
    val forward = turn.direction > 0
    val state = turn.animation
    val drag = turn.drag
    val config = rememberReaderPageCurlConfig(
        backgroundColor = background.color,
        readingDirection = readingDirection,
        dragForwardEnabled = drag != null && forward,
        dragBackwardEnabled = drag != null && !forward,
    )
    PageCurl(count = if (drag == null) 2 else 3, state = state, config = config, modifier = modifier.fillMaxSize()) { page ->
        val bitmap = if (drag == null) {
            if ((page == 0) == forward) turn.before else requireNotNull(turn.after)
        } else {
            if (page == 1 || page != 1 + turn.direction) turn.before else requireNotNull(turn.after)
        }
        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().background(background.color), contentScale = ContentScale.FillBounds)
    }
    LaunchedEffect(turn) {
        withFrameNanos { }; withFrameNanos { }
        if (drag != null && surface != null) {
            drag.bind(surface)
        } else {
            if (forward) state.next() else state.prev()
            controller.finish(turn)
        }
    }
    LaunchedEffect(turn, drag?.released) {
        if (drag?.released != true) return@LaunchedEffect
        withFrameNanos { }; withFrameNanos { }
        val settled = snapshotFlow { state.current to state.progress }
            .first { (page, progress) -> page != 1 || abs(progress) < 0.001f }
        controller.finish(turn, commit = settled.first != 1)
    }
}
