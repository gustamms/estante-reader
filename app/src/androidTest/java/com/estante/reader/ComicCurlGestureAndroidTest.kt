package com.estante.reader

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.rememberPageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.PageCurlState
import org.junit.Assert.*
import org.junit.Test

/** Isolated reproducer of the gesture hierarchy in ComicReaderScreen/PageCanvas. */
@OptIn(ExperimentalPageCurlApi::class)
class ComicCurlGestureAndroidTest {
    @Test fun oneFingerCurlAtNormalScaleMatchesThePageCurlBaseline() {
        val baseline = heldProgress(withZoom = false)
        val withZoom = heldProgress(withZoom = true)
        assertTrue("PageCurl alone must follow the held finger: $baseline", baseline > .05f)
        assertTrue("Comic page must follow the held finger at 1x: $withZoom", withZoom > .05f)
        assertEquals("Zoom support must not materially reduce curl tracking", baseline, withZoom, .1f)
    }

    @Test fun oneFingerDragPansTheComicInsteadOfTurningPageWhenZoomed() {
        val result = dragPage(withZoom = true, initialScale = 2f)
        assertTrue("Zoomed page must consume the drag as pan: ${result.pan}", result.pan.getDistance() > 20f)
        assertEquals("A pan on the zoomed page must not curl it", 0f, result.progress, .01f)
    }

    @Test fun draggingBackAndReleasingKeepsTheOriginalComicPage() {
        val result = dragPage(withZoom = true, reverseBeforeRelease = true, releaseAtEnd = true)
        assertEquals("Dragging back should remove the held curl", 0f, result.progressAfterRelease, .02f)
        assertEquals("Reversing before release must keep the original page", 1, result.pageAfterRelease)
    }

    @Test fun pinchStillZoomsWhenThePageIsAtNormalScale() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val activity = inst.startActivitySync(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var scale = 1f
        var width = 0
        var height = 0
        inst.runOnMainSync {
            activity.setContent {
                var zoom by remember { mutableFloatStateOf(1f) }
                val transform = rememberTransformableState { _, zoomChange, _, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                    scale = zoom
                }
                Box(Modifier.fillMaxSize().pageCanvasTransformable(transform, currentScale = { zoom }, enabled = true).background(Color.Yellow))
            }
        }
        Thread.sleep(500)
        inst.runOnMainSync { width = activity.window.decorView.width; height = activity.window.decorView.height }
        val down = SystemClock.uptimeMillis()
        try {
            sendPointers(inst, down, width, height, MotionEvent.ACTION_DOWN, listOf(.45f to .65f))
            sendPointers(inst, down, width, height, MotionEvent.ACTION_POINTER_DOWN, listOf(.45f to .65f, .55f to .65f), actionIndex = 1)
            for ((left, right) in listOf(.38f to .62f, .3f to .7f, .2f to .8f)) {
                Thread.sleep(30)
                sendPointers(inst, down, width, height, MotionEvent.ACTION_MOVE, listOf(left to .65f, right to .65f))
            }
            Thread.sleep(200)
            inst.runOnMainSync { /* synchronize with Compose after delivering the gesture */ }
        } finally {
            sendPointers(inst, down, width, height, MotionEvent.ACTION_POINTER_UP, listOf(.2f to .65f, .8f to .65f), actionIndex = 1)
            sendPointers(inst, down, width, height, MotionEvent.ACTION_UP, listOf(.2f to .65f))
            inst.runOnMainSync { activity.finish() }
        }
        assertTrue("Pinch at 1x must still zoom: $scale", scale > 1f)
    }

    private fun heldProgress(withZoom: Boolean): Float = dragPage(withZoom).progress

    private data class DragResult(
        val progress: Float,
        val pan: Offset,
        val progressAfterRelease: Float,
        val pageAfterRelease: Int,
    )

    private fun sendPointers(
        instrumentation: android.app.Instrumentation,
        downTime: Long,
        width: Int,
        height: Int,
        action: Int,
        normalizedPositions: List<Pair<Float, Float>>,
        actionIndex: Int = 0,
    ) {
        val properties = Array(normalizedPositions.size) { index ->
            MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coordinates = Array(normalizedPositions.size) { index ->
            val (x, y) = normalizedPositions[index]
            MotionEvent.PointerCoords().apply {
                this.x = width * x
                this.y = height * y
                pressure = 1f
                size = 1f
            }
        }
        val encodedAction = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), encodedAction,
            normalizedPositions.size, properties, coordinates, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }

    private fun dragPage(
        withZoom: Boolean,
        initialScale: Float = 1f,
        reverseBeforeRelease: Boolean = false,
        releaseAtEnd: Boolean = false,
    ): DragResult {
        val inst = InstrumentationRegistry.getInstrumentation()
        val activity = inst.startActivitySync(Intent(inst.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val state = PageCurlState(initialMax = 3, initialCurrent = 1)
        var scale = initialScale
        var accumulatedPan = Offset.Zero
        var width = 0; var height = 0
        inst.runOnMainSync {
            activity.setContent {
                val transform = rememberTransformableState { _, zoomChange, panChange, _ ->
                    scale = (scale * zoomChange).coerceIn(1f, 4f)
                    accumulatedPan += panChange
                }
                val config = rememberPageCurlConfig(
                    dragInteraction = PageCurlConfig.StartEndDragInteraction(
                        pointerBehavior = PageCurlConfig.DragInteraction.PointerBehavior.PageEdge,
                        forward = PageCurlConfig.StartEndDragInteraction.Config(Rect(.5f,0f,1f,1f), Rect(0f,0f,.5f,1f)),
                        backward = PageCurlConfig.StartEndDragInteraction.Config(Rect(0f,0f,.5f,1f), Rect(.5f,0f,1f,1f)),
                    ),
                    tapForwardEnabled = false, tapBackwardEnabled = false, tapCustomEnabled = false,
                )
                PageCurl(count = 3, state = state, config = config, modifier = Modifier.fillMaxSize()) { page ->
                    val pageModifier = if (page == 1 && withZoom) {
                        Modifier.pageCanvasTransformable(transform, currentScale = { scale }, enabled = true)
                    } else Modifier
                    Box(Modifier.fillMaxSize().then(pageModifier).background(if (page == 1) Color.Yellow else Color.Blue))
                }
            }
        }
        Thread.sleep(800)
        inst.runOnMainSync { width = activity.window.decorView.width; height = activity.window.decorView.height }
        val down = SystemClock.uptimeMillis()
        var progress = 0f
        var pan = Offset.Zero
        var progressAfterRelease = 0f
        var pageAfterRelease = 1
        var lastX = .3f
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, width*x, height*.65f, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { inst.sendPointerSync(event) } finally { event.recycle() }
        }
        try {
            send(MotionEvent.ACTION_DOWN,.9f)
            for (x in listOf(.85f,.8f,.7f,.6f,.45f,.3f)) { Thread.sleep(20); send(MotionEvent.ACTION_MOVE,x) }
            if (reverseBeforeRelease) {
                for (x in listOf(.45f,.6f,.75f,.9f)) { Thread.sleep(20); send(MotionEvent.ACTION_MOVE,x) }
                lastX = .9f
            }
            Thread.sleep(250)
            inst.runOnMainSync { progress = state.progress; pan = accumulatedPan }
        } finally {
            send(if (releaseAtEnd) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, lastX)
            Thread.sleep(250)
            inst.runOnMainSync {
                progressAfterRelease = state.progress
                pageAfterRelease = state.current
            }
            inst.runOnMainSync { activity.finish() }
        }
        return DragResult(progress, pan, progressAfterRelease, pageAfterRelease)
    }
}
