package com.estante.reader

import com.estante.reader.core.guided.ReadingDirection

/** Shared touch zones for the page based and reflowable readers. */
internal object ReaderInteraction {
    fun isCenterTap(x: Float, y: Float, width: Float, height: Float): Boolean =
        x in width * 0.30f..width * 0.70f && y in height * 0.30f..height * 0.70f

    fun edgeTurnDirection(x: Float, width: Float, readingDirection: ReadingDirection): Int? {
        val forwardAtStart = readingDirection == ReadingDirection.RIGHT_TO_LEFT
        return when {
            x < width * 0.25f -> if (forwardAtStart) 1 else -1
            x > width * 0.75f -> if (forwardAtStart) -1 else 1
            else -> null
        }
    }
}
