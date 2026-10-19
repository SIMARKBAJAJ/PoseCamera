package com.example.posecamera.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.CatalogExercise
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.demo.CUES_DISCLAIMER
import com.example.posecamera.demo.DEMO_VIEW
import com.example.posecamera.demo.DemoLift
import com.example.posecamera.demo.DemoView
import com.example.posecamera.demo.cycleNanos
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.ChamferShape
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.SahiButton
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.design.g3d.MuscleMap
import com.example.posecamera.ui.design.g3d.MuscleSide
import com.example.posecamera.ui.design.g3d.ORBIT_DEFAULT_PITCH
import com.example.posecamera.ui.design.g3d.ORBIT_MAX_PITCH
import com.example.posecamera.ui.design.g3d.ORBIT_MIN_PITCH
import com.example.posecamera.ui.design.g3d.OrbitState
import com.example.posecamera.ui.design.g3d.PLAY_FRAME_NANOS
import com.example.posecamera.ui.design.g3d.PlateStack
import com.example.posecamera.ui.design.g3d.advancePhase
import com.example.posecamera.ui.home.RecoveryChip
import com.example.posecamera.ui.home.recoveringChip
import com.example.posecamera.ui.home.rememberRecovery
import com.example.posecamera.ui.home.validSets
import com.example.posecamera.ui.design.g3d.PlumbFigure
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


/** Exercise viewer: orbitable Plumb Figure (when a demo exists), muscles, cues, equipment, Log this. */
@Composable
fun ExerciseViewerScreen(repo: LocalRepository, ex: CatalogExercise, onBack: () -> Unit, onLog: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val demo = remember(ex.slug) { demoOf(ex.slug) }
    val heat = remember(ex.slug, ex.primaryMuscles) { heatOf(ex.slug, ex.primaryMuscles) }
    val sets by remember(repo) { repo.recentSets() }.collectAsStateWithLifecycle(emptyList())
    val recovery = rememberRecovery(repo, sets)
    val cues = remember(ex.slug) { cuesFor(ex.slug) }
    val logType = remember(ex.slug) { loggableType(ex.slug) }
    Column(Modifier.fillMaxSize().background(SahiColors.Ink)) { // content then the Log bar below it: the bar can never cover content
        Column(
            Modifier.weight(1f).fillMaxWidth().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("< LIBRARY", Modifier.clickable(onClick = onBack).padding(vertical = 14.dp), style = SahiType.label.copy(color = SahiColors.Dust))
            Text(ex.name, maxLines = 3, style = SahiType.display.copy(color = SahiColors.Chalk))
            recoveringChip(primaryOf(heat), recovery)?.let { RecoveryChip(it) } // warn only, never blocks Log
            if (demo != null) ViewerHero(demo)

            if (heat.isNotEmpty()) ChamferCard(Modifier.fillMaxWidth()) {
                Text("MUSCLES", style = SahiType.label.copy(color = SahiColors.Dust))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val h = if (demo != null) 180.dp else 260.dp
                    MuscleMap(heat, MuscleSide.FRONT, Modifier.weight(1f).height(h), animateFlip = false)
                    MuscleMap(heat, MuscleSide.BACK, Modifier.weight(1f).height(h), animateFlip = false)
                }
                primaryOf(heat).takeIf { it.isNotEmpty() }?.let {
                    Text("Primary: " + it.joinToString(", ", transform = ::muscleLabel), Modifier.padding(top = 8.dp), style = SahiType.body.copy(color = SahiColors.Chalk))
                }
                secondaryOf(heat).takeIf { it.isNotEmpty() }?.let {
                    Text("Assisting: " + it.joinToString(", ", transform = ::muscleLabel), style = SahiType.body.copy(color = SahiColors.Dust))
                }
            }

            if (cues.how.isNotEmpty() || cues.watch.isNotEmpty()) ChamferCard(Modifier.fillMaxWidth()) {
                if (cues.how.isNotEmpty()) {
                    Text("FORM CUES", style = SahiType.label.copy(color = SahiColors.Dust))
                    cues.how.forEach { Bullet(it, SahiColors.Dust) }
                }
                if (cues.watch.isNotEmpty()) {
                    Text("WATCH FOR", Modifier.padding(top = 12.dp), style = SahiType.label.copy(color = SahiColors.Dust))
                    cues.watch.forEach { Bullet(it, SahiColors.Sindoor) }
                }
                if (demo != null) Text(CUES_DISCLAIMER, Modifier.padding(top = 8.dp), style = SahiType.micro.copy(color = SahiColors.Dust))
            }

            ChamferCard(Modifier.fillMaxWidth()) {
                Text("EQUIPMENT", style = SahiType.label.copy(color = SahiColors.Dust))
                Text(ex.machine?.takeIf { it.isNotBlank() } ?: "Not listed", Modifier.padding(top = 4.dp), style = SahiType.body.copy(color = SahiColors.Chalk))
                ex.difficulty?.takeIf { it.isNotBlank() }?.let { Text("Level: $it", style = SahiType.body.copy(color = SahiColors.Dust)) }
            }

            if (isBarbell(ex.slug, ex.machine)) BarbellCard(repo, ex.slug)
        }
        if (logType != null) {
            SahiButton(
                "Log this", { onLog(logType) },
                Modifier.fillMaxWidth().background(SahiColors.Ink).padding(16.dp),
            )
        }
    }
}

@Composable
private fun Bullet(text: String, dot: Color) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 9.dp).size(5.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(text, style = SahiType.body.copy(color = SahiColors.Chalk))
    }
}

/** Plates for the last logged load on this lift (or an example when there is none). Collected only for bar lifts. */
@Composable
private fun BarbellCard(repo: LocalRepository, slug: String) {
    val sets by remember(repo) { repo.recentSets() }.collectAsStateWithLifecycle(emptyList())
    val last = remember(sets, slug) { validSets(sets).firstOrNull { it.exerciseSlug == slug && (it.loadKg ?: 0.0) > 0.0 }?.loadKg?.toFloat() }
    val kg = last ?: 60f
    ChamferCard(Modifier.fillMaxWidth()) {
        Text("ON THE BAR", style = SahiType.label.copy(color = SahiColors.Dust))
        PlateStack(kg, modifier = Modifier.fillMaxWidth().height(110.dp).padding(vertical = 8.dp))
        val txt = if (kg % 1f == 0f) kg.toInt().toString() else kg.toString()
        Text(if (last != null) "Last logged: $txt kg" else "Example load: $txt kg", style = SahiType.body.copy(color = SahiColors.Dust))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerHero(lift: DemoLift) {
    val motion = LocalMotion.current
    val flat = motion.level == MotionLevel.OFF
    val canPlay = motion.allowIdleLoops // REDUCED / OFF: scrubber only
    val startView = remember(lift) { DEMO_VIEW[lift] ?: DemoView.DIAGONAL }
    val pitch0 = if (flat) 0f else ORBIT_DEFAULT_PITCH
    val orbit = remember(lift, flat) { OrbitState(Math.toRadians(startView.yawDeg.toDouble()).toFloat(), pitch0, ORBIT_MIN_PITCH, ORBIT_MAX_PITCH) }
    var preset by remember(lift) { mutableStateOf<DemoView?>(startView) }
    var target by remember { mutableStateOf(TargetMode.NONE) } // Compare (side-by-side) and Overlay (dashed) are exclusive
    var plane by remember { mutableStateOf(true) }
    var playing by remember(lift, canPlay) { mutableStateOf(canPlay) }
    val phase = remember(lift) { mutableFloatStateOf(0f) } // read in draw lambdas only
    val scope = rememberCoroutineScope()
    val job = remember { arrayOfNulls<Job>(1) }

    // Playback: one withFrameNanos loop writing the draw-only phase state, capped at ~30 fps (PLAY_FRAME_NANOS). Nothing here
    // is read during composition, so a tick redraws the figure canvas and scrubber only, never the screen.
    LaunchedEffect(playing, lift) {
        if (!playing) return@LaunchedEffect
        val cycle = cycleNanos(1f)
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = now - last
                if (dt >= PLAY_FRAME_NANOS) { last = now; phase.floatValue = advancePhase(phase.floatValue, dt, cycle) }
            }
        }
    }

    fun goTo(v: DemoView) {
        preset = v
        job[0]?.cancel()
        job[0] = scope.launch {
            val y0 = orbit.yaw; val p0 = orbit.pitch
            val y1 = nearestYaw(y0, Math.toRadians(v.yawDeg.toDouble()).toFloat())
            animate(0f, 1f, animationSpec = motion.snapSpring()) { t, _ ->
                orbit.yaw = y0 + (y1 - y0) * t; orbit.pitch = p0 + (pitch0 - p0) * t
            }
        }
    }

    val shape = ChamferShape(24.dp)
    Box(
        Modifier.fillMaxWidth().height(250.dp).background(SahiColors.Slate, shape).border(1.dp, SahiColors.Hairline, shape)
            .pointerInput(Unit) { // a finger on the figure takes over from a running preset; observes only
                awaitPointerEventScope {
                    while (true) { awaitFirstDown(requireUnconsumed = false); job[0]?.cancel(); preset = null }
                }
            },
    ) {
        PlumbFigure(lift, { phase.floatValue }, showGhost = target == TargetMode.OVERLAY, modifier = Modifier.fillMaxSize(), orbitState = orbit, showPlane = plane, compare = target == TargetMode.COMPARE)
        if (!flat && target != TargetMode.COMPARE) Text("DRAG TO ORBIT", Modifier.align(Alignment.BottomStart).padding(14.dp), style = SahiType.micro.copy(color = SahiColors.Dust))
    }

    if (flat) { // OFF / CRITICAL: flat front-only figure, so orbit/angle/target/plane controls would do nothing
        Text("Battery bachao mode: 3D off", style = SahiType.micro.copy(color = SahiColors.Dust))
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (canPlay) PlayButton(playing) { playing = !playing }
        PhaseScrubber({ phase.floatValue }, lift.keys.size, Modifier.weight(1f)) { playing = false; phase.floatValue = it }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DemoView.entries.forEach { v -> SahiChip(v.label, preset == v) { goTo(v) } }
        SahiChip("Plumb plane", plane, SahiColors.Neel) { plane = !plane }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TARGET", style = SahiType.label.copy(color = SahiColors.Dust))
        SahiChip("Compare: side by side", target == TargetMode.COMPARE, SahiColors.Neel) { target = if (target == TargetMode.COMPARE) TargetMode.NONE else TargetMode.COMPARE }
        SahiChip("Overlay: dashed", target == TargetMode.OVERLAY, SahiColors.Neel) { target = if (target == TargetMode.OVERLAY) TargetMode.NONE else TargetMode.OVERLAY }
    }
}

private enum class TargetMode { NONE, COMPARE, OVERLAY }

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Canvas(Modifier.size(44.dp).clip(CircleShape).background(SahiColors.Haldi).clickable(onClick = onClick)) {
        val c = center; val s = size.minDimension * 0.18f
        if (playing) {
            drawLine(SahiColors.Ink, Offset(c.x - s * 0.7f, c.y - s), Offset(c.x - s * 0.7f, c.y + s), s * 0.7f, StrokeCap.Round)
            drawLine(SahiColors.Ink, Offset(c.x + s * 0.7f, c.y - s), Offset(c.x + s * 0.7f, c.y + s), s * 0.7f, StrokeCap.Round)
        } else {
            drawPath(Path().apply { moveTo(c.x - s * 0.8f, c.y - s); lineTo(c.x + s * 1.1f, c.y); lineTo(c.x - s * 0.8f, c.y + s); close() }, SahiColors.Ink)
        }
    }
}

/** Bone with a joint at the current phase and a tick per keyframe. [phase] is read in the draw lambda only. */
@Composable
private fun PhaseScrubber(phase: () -> Float, keys: Int, modifier: Modifier = Modifier, onScrub: (Float) -> Unit) {
    val cb by rememberUpdatedState(onScrub)
    Canvas(
        modifier.height(44.dp)
            .pointerInput(Unit) { detectTapGestures { cb((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> change.consume(); cb((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val y = size.height / 2; val r = 8.dp.toPx(); val w = size.width - 2 * r
        val x = r + w * phase().coerceIn(0f, 1f)
        drawLine(SahiColors.Hairline, Offset(r, y), Offset(r + w, y), 2.dp.toPx(), StrokeCap.Round)
        drawLine(SahiColors.Haldi, Offset(r, y), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
        for (i in 0 until keys) drawCircle(SahiColors.Dust, 3.dp.toPx(), Offset(r + w * i / keys, y))
        drawCircle(SahiColors.Chalk, r, Offset(x, y))
        drawCircle(SahiColors.Haldi, r, Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}
