package com.example.posecamera.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.State
import androidx.compose.ui.composed
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.sync.SyncOutcome
import com.example.posecamera.sync.SyncRuntime
import com.example.posecamera.sync.SyncUi
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Rectangle with a 45 degree cut on the top-right corner (weight-plate bevel). */
class ChamferShape(private val cut: Dp = 16.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(minOf(size.width, size.height))
        return Outline.Generic(Path().apply {
            moveTo(0f, 0f); lineTo(size.width - c, 0f); lineTo(size.width, c)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        })
    }
}

@Composable
fun ChamferCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = ChamferShape()
    Column(
        modifier.clip(shape).background(SahiColors.Slate).border(1.dp, SahiColors.Hairline, shape).padding(16.dp),
        content = content,
    )
}

/** Press feedback: scales down to 0.96 while a finger is down. Observes only, never consumes. */
fun Modifier.pressScale(): Modifier = composed {
    val motion = LocalMotion.current
    var pressed by remember { mutableStateOf(false) }
    val s = animateFloatAsState(if (pressed) 0.96f else 1f, motion.micro(), label = "press")
    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
        }
        .graphicsLayer { scaleX = s.value; scaleY = s.value }
}

@Composable
fun SahiButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.pressScale().clip(RoundedCornerShape(50)).background(SahiColors.Haldi)
            .clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = SahiType.label.copy(color = SahiColors.Ink)) }
}

/** Hairline bone with a joint dot at each end. */
@Composable
fun PlumbDivider(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val y = size.height / 2; val r = 3.dp.toPx()
        drawLine(SahiColors.Hairline, Offset(r, y), Offset(size.width - r, y), 1.dp.toPx())
        drawCircle(SahiColors.Hairline, r, Offset(r, y))
        drawCircle(SahiColors.Hairline, r, Offset(size.width - r, y))
    }
}

/** Hero number that counts up to [value]. Only the Canvas redraws per frame. */
@Composable
fun StatBlock(value: Float, label: String, modifier: Modifier = Modifier, decimals: Int = 0) {
    val motion = LocalMotion.current
    val anim = remember { Animatable(if (motion.level == MotionLevel.FULL) 0f else value) } // no 0 flash when not counting up
    LaunchedEffect(value, motion) { anim.animateTo(value, motion.screen()) }
    val tm = rememberTextMeasurer()
    val style = SahiType.hero.copy(color = SahiColors.Chalk)
    fun fmt(v: Float) = String.format(Locale.US, "%.${decimals}f", v)
    val full = remember(value, decimals) { tm.measure(fmt(value), style) }
    val density = LocalDensity.current
    Column(modifier) {
        Canvas(Modifier.size(with(density) { full.size.width.toDp() }, with(density) { full.size.height.toDp() })) {
            drawText(tm, fmt(anim.value), Offset.Zero, style)
        }
        Text(label.uppercase(), style = SahiType.label.copy(color = SahiColors.Dust))
    }
}

/** Pulse ring around a joint dot; rings once whenever [trigger] changes (0 = never rung). */
@Composable
fun JointPing(trigger: Int, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    val motion = LocalMotion.current
    val p = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        p.snapTo(0f)
        p.animateTo(1f, motion.ping())
    }
    Canvas(modifier) {
        val r = size.minDimension / 2; val t = p.value
        drawCircle(color, r * 0.25f)
        if (t < 1f) drawCircle(color.copy(alpha = 1f - t), r * (0.25f + 0.75f * t), style = Stroke(2.dp.toPx()))
    }
}

enum class SyncPillState(val label: String, val color: Color) {
    SYNCING("Syncing", SahiColors.Neel), OFFLINE("Offline", SahiColors.Dust), NOT_SYNCED("Not synced", SahiColors.Dust),
    SYNCED("Synced", SahiColors.Neel), LOGIN("Log in", SahiColors.Sindoor),
}

fun syncPillState(s: SyncUi) = when {
    s.needsLogin -> SyncPillState.LOGIN
    s.running -> SyncPillState.SYNCING
    s.lastOutcome == SyncOutcome.OK -> SyncPillState.SYNCED
    s.lastOutcome == null -> SyncPillState.NOT_SYNCED // nothing has run yet this launch: neutral, not "Offline"
    s.lastOutcome == SyncOutcome.PAUSED_AUTH -> SyncPillState.LOGIN
    else -> SyncPillState.OFFLINE
}

@Composable
fun SyncPill(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ui by remember(ctx) { SyncRuntime.get(ctx).status }.collectAsStateWithLifecycle()
    SyncPillView(syncPillState(ui), modifier)
}

/** Stateless pill, also used by the gallery. */
@Composable
fun SyncPillView(state: SyncPillState, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val draw: State<Float> = if (state == SyncPillState.SYNCING && motion.allowIdleLoops)
        rememberInfiniteTransition(label = "sync").animateFloat(0f, 1f, infiniteRepeatable(tween(1100)), label = "bone")
    else remember { androidx.compose.runtime.mutableFloatStateOf(0.6f) }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(SahiColors.Slate2).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp, 16.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val y = size.height / 2; val r = 3.dp.toPx(); val w = 1.5.dp.toPx()
                val x1 = size.width - r
                val dashed = if (state == SyncPillState.OFFLINE) PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) else null
                val end = if (state == SyncPillState.SYNCING) r + (x1 - r) * draw.value else x1
                drawCircle(state.color, r, Offset(r, y))
                if (state == SyncPillState.LOGIN) { // broken bone
                    drawLine(state.color, Offset(r, y), Offset(size.width * 0.4f, y), w, StrokeCap.Round)
                    drawLine(state.color, Offset(size.width * 0.6f, y), Offset(x1, y), w, StrokeCap.Round)
                } else drawLine(state.color, Offset(r, y), Offset(end, y), w, StrokeCap.Round, dashed)
                if (state != SyncPillState.SYNCED) drawCircle(state.color, r, Offset(end, y), style = Stroke(w))
            }
            if (state == SyncPillState.SYNCED) {
                JointPing(1, state.color, Modifier.size(16.dp).align(Alignment.CenterEnd))
            }
        }
        Text(state.label, Modifier.padding(start = 8.dp), style = SahiType.micro.copy(color = SahiColors.Chalk))
    }
}

/** Ring progress with a joint at the tip. [progress] 0..1; arrives with the snap spring (overshoot allowed). */
@Composable
fun RepRing(progress: Float, modifier: Modifier = Modifier.size(120.dp)) {
    val motion = LocalMotion.current
    val p = animateFloatAsState(progress, motion.snapSpring(), label = "rep")
    Canvas(modifier) {
        val w = 8.dp.toPx(); val r = (size.minDimension - w) / 2; val c = center
        drawCircle(SahiColors.Hairline, r, c, style = Stroke(w))
        val v = p.value
        drawArc(
            SahiColors.Haldi, -90f, 360f * v.coerceIn(0f, 1f), false,
            Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(w, cap = StrokeCap.Round),
        )
        val a = Math.toRadians(-90.0 + 360.0 * v)
        drawCircle(SahiColors.Chalk, w * 0.8f, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
    }
}

/** Ring plus inner depth well filled from the bottom by [depth] 0..1; the well drifts slightly with depth (parallax). */
@Composable
fun DepthGauge(depth: Float, modifier: Modifier = Modifier.size(120.dp)) {
    val motion = LocalMotion.current
    val d = animateFloatAsState(depth, motion.snapSpring(), label = "depth")
    Canvas(modifier) {
        val w = 6.dp.toPx(); val r = (size.minDimension - w) / 2
        drawCircle(SahiColors.Hairline, r, center, style = Stroke(w))
        val v = d.value.coerceIn(0f, 1f)
        val wr = r * 0.7f
        val wc = Offset(center.x, center.y + (v - 0.5f) * r * 0.12f)
        val well = Path().apply { addOval(androidx.compose.ui.geometry.Rect(wc, wr)) }
        drawPath(well, SahiColors.Slate2)
        clipPath(well) {
            drawRect(SahiColors.Haldi, Offset(wc.x - wr, wc.y + wr - 2 * wr * v), Size(2 * wr, 2 * wr * v))
        }
        drawArc(SahiColors.Haldi, -90f, 360f * v, false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r),
            style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Vertical plumb line; the bob hangs still at deviation 0 and swings out (spring, no idle loop) toward -1..1. */
@Composable
fun PlumbLine(deviation: Float, modifier: Modifier = Modifier.size(48.dp, 160.dp)) {
    val motion = LocalMotion.current
    val dev = animateFloatAsState(
        deviation.coerceIn(-1f, 1f),
        if (motion.level == MotionLevel.FULL) spring(dampingRatio = 0.25f, stiffness = 90f) else motion.snapSpring(),
        label = "plumb",
    )
    Canvas(modifier) {
        val len = size.height - 10.dp.toPx(); val top = Offset(size.width / 2, 0f)
        val a = Math.toRadians(dev.value * 25.0)
        val bob = Offset(top.x + len * sin(a).toFloat(), len * cos(a).toFloat())
        val col = if (kotlin.math.abs(deviation) < 0.15f) SahiColors.Sahi else SahiColors.Sindoor
        drawCircle(SahiColors.Hairline, 3.dp.toPx(), top)
        drawLine(SahiColors.Hairline, top, bob, 1.dp.toPx())
        drawCircle(col, 7.dp.toPx(), bob)
    }
}
