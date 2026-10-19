package com.example.posecamera.demo

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp

private val Ghost = Color(0x6600E676)
private const val FAR_ALPHA = 0.55f

/** Reused per-figure buffers: nothing is allocated while drawing. */
private class FigureBuf {
    val ang = FloatArray(10)
    val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
    val px = FloatArray(Fk.N); val py = FloatArray(Fk.N)
}

/**
 * Looping stick-figure demo with a faint ghost of the lift's good-form target pose.
 * animationScale 0 = no animation: tap the figure to toggle start / target frame.
 * (Not using ui.animSpec: that is for tween states, this is a continuous frame clock; same scale semantics.)
 */
@Composable
fun ExerciseDemo(
    lift: DemoLift,
    view: DemoView,
    animationScale: Float,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
) {
    val cur = remember { FigureBuf() }
    val ghost = remember { FigureBuf() }
    var tick by remember { mutableIntStateOf(0) }
    var second by remember { mutableStateOf(false) }
    val isStatic = animationScale <= 0f
    val ink = MaterialTheme.colorScheme.onSurface
    val fit = remember(lift, view) { demoFit(lift, view) }
    var elapsed by remember { mutableStateOf(0L) }

    LaunchedEffect(lift, animationScale, playing) {
        if (isStatic || !playing) return@LaunchedEffect
        val cycle = cycleNanos(animationScale); val gap = redrawGapNanos(animationScale)
        var prev = -1L; var lastDraw = 0L
        while (true) withFrameNanos { n ->
            if (prev >= 0) elapsed = (elapsed + (n - prev)) % cycle
            prev = n
            if (n - lastDraw >= gap) { lastDraw = n; tick++ }
        }
    }

    Canvas(modifier.clickable(enabled = isStatic) { second = !second }) {
        tick // read so the draw re-runs on each tick
        if (isStatic) staticPose(lift, second, cur.ang)
        else poseAt(lift, elapsed.toFloat() / cycleNanos(animationScale), cur.ang)
        demoSolve(lift, cur.ang, cur.x, cur.y, cur.z)
        staticPose(lift, true, ghost.ang)
        demoSolve(lift, ghost.ang, ghost.x, ghost.y, ghost.z)

        val c = Fk.cosYaw(view); val s = Fk.sinYaw(view)
        val margin = 12.dp.toPx()
        val scale = fit.scale(size.width, size.height, margin)
        val gy = fit.sy(0f, size.height, margin, scale)
        drawLine(ink.copy(alpha = 0.6f), Offset(0f, gy), Offset(size.width, gy), 3f)
        DEMO_PROPS[lift]?.let { props ->
            // Front / 45 deg: fixed machine lines dimmer so the body reads in front of them
            val frame = ink.copy(alpha = if (view == DemoView.SIDE) 0.6f else 0.3f); val held = ink.copy(alpha = 0.6f)
            for (i in props.indices) {
                val p = props[i]
                if (!p.shownIn(view)) continue
                val faint = p.isFaint(view)
                prop(p, cur, fit, c, s, scale, size, margin, if (faint) ink.copy(alpha = 0.2f) else if (p.follows) held else frame,
                    if (faint) 3f else 5f)
                if (p.ghostIn(view)) prop(p, ghost, fit, c, s, scale, size, margin, Ghost) // held bar stays in the ghost hands
            }
        }
        draw(ghost, fit, c, s, scale, size, margin, Ghost, true)
        draw(cur, fit, c, s, scale, size, margin, ink, false)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.prop(
    pr: DemoProp, f: FigureBuf, fit: DemoFit, c: Float, s: Float, scale: Float,
    size: androidx.compose.ui.geometry.Size, margin: Float, col: Color, width: Float = 5f,
) = drawLine(col,
    Offset(fit.sx(Fk.projectX(pr.wx(0, f.x), pr.wz(0), c, s), size.width, scale), fit.sy(pr.wy(0, f.y), size.height, margin, scale)),
    Offset(fit.sx(Fk.projectX(pr.wx(1, f.x), pr.wz(1), c, s), size.width, scale), fit.sy(pr.wy(1, f.y), size.height, margin, scale)),
    width, StrokeCap.Round)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.draw(
    f: FigureBuf, fit: DemoFit, c: Float, s: Float, scale: Float, size: androidx.compose.ui.geometry.Size,
    margin: Float, ink: Color, isGhost: Boolean,
) {
    for (i in 0 until Fk.N) {
        f.px[i] = fit.sx(Fk.projectX(f.x[i], f.z[i], c, s), size.width, scale)
        f.py[i] = fit.sy(f.y[i], size.height, margin, scale)
    }
    val base = if (isGhost) 9f else 6f
    for (b in Fk.BONE_A.indices) {
        val a = Fk.BONE_A[b]; val e = Fk.BONE_B[b]
        // right (far-side) limbs: dimmer and thinner, for figure and ghost alike
        val far = Fk.IS_RIGHT[e]
        val col = if (far) ink.copy(alpha = ink.alpha * FAR_ALPHA) else ink
        drawLine(col, Offset(f.px[a], f.py[a]), Offset(f.px[e], f.py[e]), if (far) base * 0.6f else base, StrokeCap.Round)
    }
    drawCircle(ink, Fk.L_HEAD * 0.7f * scale, Offset(f.px[Fk.HEAD], f.py[Fk.HEAD]),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isGhost) 4f else 3f))
}

/** Lift list + demo + view toggle + play/pause + generic cues. Entry point is wired in by the caller. */
@Composable
fun DemoScreen(onBack: () -> Unit, animationScale: Float = 1f) {
    BackHandler(onBack = onBack)
    // The screen is always dark (theme background): force light status-bar icons, restore on exit.
    val host = LocalView.current
    DisposableEffect(host) {
        val bars = (host.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, host) }
        val old = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = false
        onDispose { if (old != null) bars.isAppearanceLightStatusBars = old }
    }
    var lift by remember { mutableStateOf(DemoLift.SQUAT) }
    var view by remember { mutableStateOf(DemoView.DIAGONAL) }
    var playing by remember { mutableStateOf(true) }
    var group by remember { mutableIntStateOf(0) } // SQUAT is in group 0 (Legs)
    val tight = PaddingValues(horizontal = 6.dp)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Compact picker: Back + 4 group chips on one row, the chosen group's lifts on one scrolling row
        // (keeps the figure and the Pause / view row on screen without scrolling).
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onBack, contentPadding = tight) { Text("Back") }
            DEMO_GROUPS.forEachIndexed { gi, (section, _) ->
                val pickGroup: () -> Unit = { group = gi }
                if (gi == group) Button(onClick = pickGroup, Modifier.weight(1f), contentPadding = tight) { Text(section) }
                else OutlinedButton(onClick = pickGroup, Modifier.weight(1f), contentPadding = tight) { Text(section) }
            }
        }
        val lifts = DEMO_GROUPS[group].second
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lifts.size) { i ->
                val l = lifts[i]
                val pick: () -> Unit = { lift = l; DEMO_VIEW[l]?.let { view = it } }
                if (l == lift) Button(onClick = pick) { Text(l.label) }
                else OutlinedButton(onClick = pick) { Text(l.label) }
            }
        }
        ExerciseDemo(lift, view, animationScale, Modifier.fillMaxWidth().height(280.dp), playing)
        Text(
            lift.keys.joinToString(" > ") { it.phase } + " (green ghost = " + lift.keys[lift.target].phase + ")",
            style = MaterialTheme.typography.bodySmall,
        )
        if (animationScale <= 0f) Text("Animations off: tap the figure to switch frames.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (animationScale > 0f) OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) "Pause" else "Play") }
            DemoView.entries.forEach { v ->
                if (v == view) Button(onClick = { view = v }) { Text(v.label) }
                else OutlinedButton(onClick = { view = v }) { Text(v.label) }
            }
        }
        Text("Key cues", style = MaterialTheme.typography.titleMedium)
        DEMO_CUES[lift].orEmpty().forEach { Text("- $it") }
        Text(CUES_DISCLAIMER, style = MaterialTheme.typography.bodySmall)
    }
    }
}
