package com.example.posecamera.ui.design.g3d

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Screen-space result of [Cam3.project]: x right, y UP (flip when drawing), depth = distance from the eye. */
class P3(var sx: Float = 0f, var sy: Float = 0f, var depth: Float = 0f)

/**
 * Orbit camera looking at the origin from [dist] away. Yaw (rad) turns about the vertical axis:
 * 0 = side view (screen x = world x), PI/2 = front (screen x = world z), same convention as Fk.projectX.
 */
class Cam3(val yaw: Float, val pitch: Float, val dist: Float, val ortho: Boolean = false) {
    /** Perspective size factor at [depth] (1 when orthographic). */
    fun scaleAt(depth: Float) = if (ortho) 1f else dist / depth

    private val cy = cos(yaw); private val sy = sin(yaw); private val cp = cos(pitch); private val sp = sin(pitch)

    /** Writes into [out]: pass a reused P3 on hot paths (the default allocates). */
    fun project(x: Float, y: Float, z: Float, out: P3 = P3()): P3 {
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        val y2 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        val depth = maxOf(z2 + dist, MIN_DEPTH) // at/behind the eye: pin to the near plane, never divide by <= 0
        val f = scaleAt(depth)
        out.sx = x1 * f; out.sy = y2 * f; out.depth = depth
        return out
    }
}

const val MIN_DEPTH = 0.05f

/** Mutable orbit angles. Reads in a draw lambda only invalidate drawing, not composition. */
class OrbitState(yaw: Float, pitch: Float, val minPitch: Float = -MAX_PITCH, val maxPitch: Float = MAX_PITCH) {
    var yaw by mutableFloatStateOf(yaw)
    var pitch by mutableFloatStateOf(pitch)
    fun cam(dist: Float) = Cam3(yaw, pitch, dist)
}

private const val RAD_PER_PX = 0.01f
const val MAX_PITCH = 1.2f

/** Drag to orbit. FULL adds fling inertia; REDUCED has none; OFF ignores input entirely. */
fun Modifier.orbitDrag(state: OrbitState): Modifier = composed {
    val level = LocalMotion.current.level
    if (level == MotionLevel.OFF) return@composed this
    pointerInput(state, level) {
        coroutineScope {
            val tracker = VelocityTracker()
            var flingJob: Job? = null
            detectDragGestures(
                onDragStart = { flingJob?.cancel(); tracker.resetTracking() }, // one writer of yaw/pitch at a time
                onDragEnd = { if (level == MotionLevel.FULL) flingJob = fling(state, tracker) },
                onDragCancel = { },
                onDrag = { change: PointerInputChange, d: Offset ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    state.yaw += d.x * RAD_PER_PX
                    state.pitch = (state.pitch + d.y * RAD_PER_PX).coerceIn(state.minPitch, state.maxPitch)
                },
            )
        }
    }
}

private fun CoroutineScope.fling(state: OrbitState, tracker: VelocityTracker): Job = launch {
    val v = tracker.calculateVelocity()
    launch {
        var last = 0f
        Animatable(0f).animateDecay(v.x * RAD_PER_PX, exponentialDecay()) { state.yaw += value - last; last = value }
    }
    launch {
        var last = 0f
        Animatable(0f).animateDecay(v.y * RAD_PER_PX, exponentialDecay()) {
            state.pitch = (state.pitch + value - last).coerceIn(state.minPitch, state.maxPitch); last = value
        }
    }
}

/**
 * Collects lines/dots in world coords, then draws them far-to-near. Sizes are world units: they shrink with
 * perspective, and alpha fades with depth so the near side reads in front.
 */
class Scene3(private var cam: Cam3) {
    private class Item {
        var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f; var depth = 0f
        var color = Color.Unspecified; var size = 0f; var dot = false; var ring = false
    }

    // Pooled: call reset() each frame and no Item is allocated once the pool has grown to the frame's size.
    private val items = ArrayList<Item>()
    private var count = 0
    private val pa = P3(); private val pb = P3() // project() scratch: no per-call allocation

    fun reset(cam: Cam3) { this.cam = cam; count = 0 }

    private fun next(): Item { if (count == items.size) items.add(Item()); return items[count++] }

    fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, color: Color, width: Float) {
        val a = cam.project(x0, y0, z0, pa); val b = cam.project(x1, y1, z1, pb)
        val d = (a.depth + b.depth) / 2
        next().also {
            it.ax = a.sx; it.ay = a.sy; it.bx = b.sx; it.by = b.sy; it.depth = d
            it.color = color; it.size = width * cam.scaleAt(d); it.dot = false; it.ring = false
        }
    }

    fun dot(x: Float, y: Float, z: Float, color: Color, radius: Float, ring: Boolean = false) {
        val p = cam.project(x, y, z, pa)
        next().also {
            it.ax = p.sx; it.ay = p.sy; it.depth = p.depth
            it.color = color; it.size = radius * cam.scaleAt(p.depth); it.dot = true; it.ring = ring
        }
    }

    /** [scale] = pixels per world unit at the focus distance; [center] = screen point of the camera target. */
    fun render(scope: DrawScope, center: Offset, scale: Float) = with(scope) {
        items.subList(0, count).sortByDescending { it.depth }
        for (n in 0 until count) {
            val it = items[n]
            val shade = if (cam.ortho) 1f else (1f - (it.depth - cam.dist) * 0.6f).coerceIn(0.4f, 1f)
            val c = if (shade == 1f) it.color else it.color.copy(alpha = it.color.alpha * shade)
            val ax = center.x + it.ax * scale; val ay = center.y - it.ay * scale
            if (it.dot) {
                if (it.ring) drawCircle(c, it.size * scale, Offset(ax, ay), style = Stroke(3f))
                else drawCircle(c, it.size * scale, Offset(ax, ay))
            } else {
                drawLine(c, Offset(ax, ay), Offset(center.x + it.bx * scale, center.y - it.by * scale), it.size * scale, StrokeCap.Round)
            }
        }
    }
}
