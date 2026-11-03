package com.estante.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.estante.reader.core.guided.ReadingDirection
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.rememberPageCurlConfig

/** Shared page-curl appearance and edge mapping for books, comics, and PDFs. */
@OptIn(ExperimentalPageCurlApi::class)
internal fun readerPageCurlDragInteraction(
    readingDirection: ReadingDirection,
): PageCurlConfig.StartEndDragInteraction {
    val leftHalf = Rect(0f, 0f, 0.5f, 1f)
    val rightHalf = Rect(0.5f, 0f, 1f, 1f)
    val forwardStart = if (readingDirection == ReadingDirection.LEFT_TO_RIGHT) rightHalf else leftHalf
    val forwardEnd = if (readingDirection == ReadingDirection.LEFT_TO_RIGHT) leftHalf else rightHalf
    return PageCurlConfig.StartEndDragInteraction(
        pointerBehavior = PageCurlConfig.DragInteraction.PointerBehavior.PageEdge,
        forward = PageCurlConfig.StartEndDragInteraction.Config(forwardStart, forwardEnd),
        backward = PageCurlConfig.StartEndDragInteraction.Config(forwardEnd, forwardStart),
    )
}

@OptIn(ExperimentalPageCurlApi::class)
@Composable
internal fun rememberReaderPageCurlConfig(
    backgroundColor: Color,
    readingDirection: ReadingDirection,
    dragForwardEnabled: Boolean = false,
    dragBackwardEnabled: Boolean = false,
): PageCurlConfig {
    return rememberPageCurlConfig(
        backPageColor = backgroundColor,
        backPageContentAlpha = 0.08f,
        shadowColor = Color.Black,
        shadowAlpha = 0.28f,
        shadowRadius = 18.dp,
        shadowOffset = DpOffset((-6).dp, 0.dp),
        dragInteraction = readerPageCurlDragInteraction(readingDirection),
        dragForwardEnabled = dragForwardEnabled,
        dragBackwardEnabled = dragBackwardEnabled,
        tapForwardEnabled = false,
        tapBackwardEnabled = false,
        tapCustomEnabled = false,
    )
}
