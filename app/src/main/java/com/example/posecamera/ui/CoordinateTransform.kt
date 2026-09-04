package com.example.posecamera.ui

data class DisplayPoint(val x: Float, val y: Float)

fun viewportPoint(
    normalizedX: Float,
    normalizedY: Float,
    mirrorHorizontally: Boolean,
    viewWidth: Float,
    viewHeight: Float,
): DisplayPoint {
    if (viewWidth <= 0f || viewHeight <= 0f) {
        return DisplayPoint(0f, 0f)
    }
    val displayX = if (mirrorHorizontally) 1f - normalizedX else normalizedX
    return DisplayPoint(
        x = displayX * viewWidth,
        y = normalizedY * viewHeight,
    )
}
