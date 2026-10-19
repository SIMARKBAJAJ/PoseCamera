package com.example.posecamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.example.posecamera.pose.PoseEngine
import com.example.posecamera.pose.PoseFrame
import com.example.posecamera.pose.FormState
import com.example.posecamera.power.OverlayDetail
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.SahiColors

@Composable
fun PoseOverlay(
    frame: PoseFrame?,
    modifier: Modifier = Modifier,
    detail: OverlayDetail = OverlayDetail.FULL,
    reduceScaling: Boolean = false,
) {
    val mapped = remember { FloatArray(PoseEngine.LANDMARK_COUNT * 2) }
    val minimal = detail == OverlayDetail.MINIMAL
    val glow = LocalMotion.current.allowGlow // off in SAVER/CRITICAL
    Canvas(modifier.fillMaxSize()) {
        val current = frame ?: return@Canvas
        fun map(index: Int): DisplayPoint {
            val landmark = current.landmarks[index]
            return viewportPoint(
                landmark.x,
                landmark.y,
                current.mirrorHorizontally,
                size.width,
                size.height,
            )
        }
        // reduceScaling: map each landmark once per frame into reused storage, not once per use.
        val cached = reduceScaling && current.landmarks.size <= PoseEngine.LANDMARK_COUNT
        if (cached) {
            for (i in current.landmarks.indices) {
                val p = map(i)
                mapped[2 * i] = p.x
                mapped[2 * i + 1] = p.y
            }
        }
        fun point(index: Int): Offset {
            if (cached) return Offset(mapped[2 * index], mapped[2 * index + 1])
            val p = map(index)
            return Offset(p.x, p.y)
        }

        PoseEngine.connections.forEach { (start, end) ->
            // Minimal detail skips the face landmarks (0..10).
            if (minimal && (start < FIRST_BODY_LANDMARK || end < FIRST_BODY_LANDMARK)) return@forEach
            if (start < current.landmarks.size && end < current.landmarks.size) {
                val active = connectionKey(start, end) in current.analysis.activeConnections
                if (glow && active) drawLine(current.analysis.state.overlayColor().copy(alpha = 0.3f), point(start), point(end), 12f, StrokeCap.Round)
                drawLine(
                    color = if (active) {
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
            val active = index in current.analysis.activeLandmarks
            if (minimal && !active) return@forEachIndexed // minimal: only the joints being judged
            val color = if (active) current.analysis.state.overlayColor() else SkeletonColor
            drawCircle(color = color, radius = 5f, center = point(index))
        }
    }
}

private const val FIRST_BODY_LANDMARK = 11

private val SkeletonColor = SahiColors.Chalk.copy(alpha = 0.92f)

private fun connectionKey(start: Int, end: Int): Pair<Int, Int> =
    minOf(start, end) to maxOf(start, end)

private fun FormState.overlayColor(): Color = when (this) {
    FormState.GREEN -> SahiColors.Sahi
    FormState.YELLOW -> SahiColors.Haldi
    FormState.RED -> SahiColors.Sindoor
    FormState.GREY -> SahiColors.Dust
}
