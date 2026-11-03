package com.estante.reader

import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.Modifier

/** Lets page turns own one-finger drags until the page is zoomed in. Pinch zoom remains enabled. */
internal fun Modifier.pageCanvasTransformable(
    state: TransformableState,
    currentScale: () -> Float,
    enabled: Boolean,
): Modifier = transformable(
    state = state,
    canPan = { currentScale() > 1f },
    enabled = enabled,
)
