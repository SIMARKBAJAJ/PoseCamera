package com.example.posecamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.example.posecamera.pose.PoseEngine
import com.example.posecamera.pose.PoseFrame
import com.example.posecamera.pose.FormState

@Composable
fun PoseOverlay(frame: PoseFrame?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val current = frame ?: return@Canvas
        fun point(index: Int): Offset {
            val landmark = current.landmarks[index]
            val mapped = viewportPoint(
                landmark.x,
                landmark.y,
                current.mirrorHorizontally,
                size.width,
                size.height,
            )
            return Offset(mapped.x, mapped.y)
        }

        PoseEngine.connections.forEach { (start, end) ->
            if (start < current.landmarks.size && end < current.landmarks.size) {
                drawLine(
                    color = if (connectionKey(start, end) in current.analysis.activeConnections) {
                        current.analysis.state.overlayColor()
                    } else {
                        SkeletonColor
                    },
                    start = point(start),
                    end = point(end),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            }
        }
        current.landmarks.forEachIndexed { index, _ ->
            val color = if (index in current.analysis.activeLandmarks) {
                current.analysis.state.overlayColor()
            } else {
                SkeletonColor
            }
            drawCircle(color = color, radius = 5f, center = point(index))
        }
    }
}

private val SkeletonColor = Color.White.copy(alpha = 0.92f)
private val GreenColor = Color(0xFF4ADE80)
private val YellowColor = Color(0xFFFACC15)
private val RedColor = Color(0xFFF87171)
private val GreyColor = Color(0xFF9CA3AF)

private fun connectionKey(start: Int, end: Int): Pair<Int, Int> =
    minOf(start, end) to maxOf(start, end)

private fun FormState.overlayColor(): Color = when (this) {
    FormState.GREEN -> GreenColor
    FormState.YELLOW -> YellowColor
    FormState.RED -> RedColor
    FormState.GREY -> GreyColor
}
