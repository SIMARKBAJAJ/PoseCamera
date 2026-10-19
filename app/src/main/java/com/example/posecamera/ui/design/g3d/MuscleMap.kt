package com.example.posecamera.ui.design.g3d

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.SahiColors
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class MuscleSide { FRONT, BACK }

private val WARM = androidx.compose.ui.graphics.Color(0xFF7A5A14) // darkest trained shade (any trained group, however small)

/** Polygon in a 100 x 200 box (x,y pairs). [mirror] adds the right-hand copy (x -> 100 - x). */
private class Region(val key: String, val mirror: Boolean, vararg val pts: Float)

private val BASE = listOf( // silhouette parts with no muscle key: head neck, tapered torso, hands, knees, feet (drawn first)
    Region("torso", true, 50f, 34f, 38f, 35f, 31f, 40f, 33f, 60f, 38f, 80f, 41f, 100f, 37f, 112f, 50f, 112f),
    Region("neck", true, 45f, 22f, 44f, 33f, 50f, 36f, 50f, 22f),
    Region("hands", true, 14f, 109f, 24f, 110f, 23f, 120f, 15f, 119f),
    Region("knees", true, 39f, 150f, 46f, 150f, 45f, 158f, 38f, 158f),
    Region("feet", true, 36f, 190f, 44f, 190f, 47f, 197f, 33f, 197f),
)

private val FRONT = BASE + listOf(
    Region("shoulders", true, 38f, 36f, 30f, 34f, 22f, 40f, 20f, 52f, 28f, 50f, 36f, 44f),
    Region("chest", true, 49f, 38f, 37f, 40f, 33f, 50f, 35f, 62f, 49f, 62f),
    Region("biceps", true, 20f, 54f, 27f, 53f, 27f, 76f, 20f, 78f),
    Region("forearms", true, 17f, 82f, 25f, 81f, 23f, 108f, 15f, 107f),
    Region("abs", false, 44f, 64f, 56f, 64f, 55f, 100f, 45f, 100f),
    Region("quads", true, 35f, 108f, 47f, 108f, 46f, 150f, 39f, 150f, 34f, 130f),
    Region("calves", true, 38f, 158f, 45f, 158f, 43f, 190f, 39f, 190f),
)

private val BACK = BASE + listOf(
    Region("traps", false, 50f, 31f, 41f, 35f, 37f, 44f, 50f, 60f, 63f, 44f, 59f, 35f),
    Region("shoulders", true, 38f, 36f, 30f, 34f, 22f, 40f, 20f, 52f, 28f, 50f, 36f, 44f),
    Region("triceps", true, 20f, 54f, 27f, 53f, 27f, 76f, 20f, 78f),
    Region("forearms", true, 17f, 82f, 25f, 81f, 23f, 108f, 15f, 107f),
    Region("lats", true, 36f, 62f, 48f, 64f, 48f, 92f, 40f, 92f, 35f, 72f),
    Region("lower_back", false, 44f, 94f, 56f, 94f, 56f, 106f, 44f, 106f),
    Region("glutes", true, 36f, 108f, 49f, 108f, 49f, 128f, 36f, 126f),
    Region("hamstrings", true, 36f, 132f, 47f, 132f, 46f, 150f, 39f, 150f),
    Region("calves", true, 38f, 158f, 45f, 158f, 43f, 190f, 39f, 190f),
)

private fun Region.buildPath(mirrored: Boolean) = Path().apply {
    for (i in pts.indices step 2) {
        val x = if (mirrored) 100f - pts[i] else pts[i]
        if (i == 0) moveTo(x, pts[i + 1]) else lineTo(x, pts[i + 1])
    }
    close()
}

// Geometry is in the fixed 100 x 200 box, so paths are built once (not per frame) and only read when drawing.
private class Built(val region: Region, val paths: List<Path>)
private fun build(rs: List<Region>) = rs.map { Built(it, if (it.mirror) listOf(it.buildPath(false), it.buildPath(true)) else listOf(it.buildPath(false))) }
private val FRONT_PATHS = build(FRONT)
private val BACK_PATHS = build(BACK)

/** Body outline + muscle groups tinted Slate2 -> Haldi by [heat] (0..1 per key, e.g. "chest", "lower_back"). */
@Composable
fun MuscleMap(heat: Map<String, Float>, side: MuscleSide, modifier: Modifier = Modifier, animateFlip: Boolean = true,
    untouched: Color = SahiColors.Slate2, outline: Color = SahiColors.Hairline,
    colors: Map<String, Color>? = null, // when set, paints these keys directly (recovery mode) and ignores [heat]
) {
    val motion = LocalMotion.current
    val rot = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(side) }
    LaunchedEffect(side) {
        if (side == shown) return@LaunchedEffect
        if (animateFlip && motion.level == MotionLevel.FULL) { // caller-driven turns and other levels: instant swap otherwise
            try {
                rot.animateTo(90f, tween(160)); shown = side; rot.snapTo(-90f); rot.animateTo(0f, tween(160))
            } finally { // cancelled by another toggle: never leave the card edge-on; `shown` already matches the half reached
                withContext(NonCancellable) { rot.snapTo(0f) }
            }
        } else shown = side
    }
    val camDist = 12f * LocalDensity.current.density
    Canvas(modifier.graphicsLayer { rotationY = rot.value; cameraDistance = camDist }) {
        val s = minOf(size.width / 100f, size.height / 200f)
        translate((size.width - 100f * s) / 2, (size.height - 200f * s) / 2) {
            scale(s, s, Offset.Zero) {
                val line = Stroke(1f / s * 1.2f)
                drawCircle(untouched, 9f, Offset(50f, 14f)) // head
                drawCircle(outline, 9f, Offset(50f, 14f), style = line)
                for (b in if (shown == MuscleSide.FRONT) FRONT_PATHS else BACK_PATHS) {
                    val h = (heat[b.region.key] ?: 0f).coerceIn(0f, 1f)
                    val c = if (colors != null) colors[b.region.key] ?: untouched
                    else if (h <= 0f) untouched else lerp(WARM, SahiColors.Haldi, h) // trained groups jump clear of untouched ones
                    for (p in b.paths) {
                        drawPath(p, c)
                        drawPath(p, outline, style = line)
                    }
                }
            }
        }
    }
}
