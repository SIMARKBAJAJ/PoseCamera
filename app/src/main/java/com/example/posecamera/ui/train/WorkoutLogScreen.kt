package com.example.posecamera.ui.train

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.posecamera.ui.library.isBarbell
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.data.LocalSet
import com.example.posecamera.ui.library.demoOf
import com.example.posecamera.lifts.LiftConfig
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.JointPing
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.SahiButton
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.design.g3d.PlateStack
import com.example.posecamera.ui.design.g3d.PlumbFigure
import com.example.posecamera.ui.home.RecoveryChip
import com.example.posecamera.ui.home.isPr
import com.example.posecamera.ui.home.recoveringChip
import com.example.posecamera.ui.home.rememberRecovery
import com.example.posecamera.ui.library.heatOf
import com.example.posecamera.ui.library.primaryOf
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class SetRow(kg: Double, reps: Int, rpe: Double?, done: Boolean) {
    var kg by mutableStateOf(kg)
    var reps by mutableStateOf(reps)
    var rpe by mutableStateOf(rpe)
    var done by mutableStateOf(done)
    var pings by mutableIntStateOf(0)
    var score: Int? = null // stored SET SCORE for saved rows; null = nothing to show
}

private fun kgText(kg: Double) = String.format(Locale.US, if (kg % 1.0 == 0.0) "%.0f" else "%.1f", kg)

@Composable
fun WorkoutLogScreen(repo: LocalRepository, config: LiftConfig, onBack: () -> Unit) =
    WorkoutLogScreen(repo, config.catalogSlug, config.type.displayLabel, true, onBack) { reps, kg, rpe ->
        repo.saveSet(manualSummary(config, reps), kg, rpe) // same save path as the camera flow
    }

/** Generic manual logger. [showKg] false = bodyweight skill step (no load stepper, load saved as null). */
@Composable
fun WorkoutLogScreen(
    repo: LocalRepository, key: String, label: String, showKg: Boolean, onBack: () -> Unit,
    save: suspend (reps: Int, kg: Double?, rpe: Double?) -> Unit,
) {
    val kg0 = if (showKg) 20.0 else 0.0
    val history by remember(repo) { repo.recentSets() }.collectAsStateWithLifecycle(null)
    val recovery = rememberRecovery(repo, history ?: emptyList())
    val scope = rememberCoroutineScope()
    val rows =remember(key) { mutableStateListOf<SetRow>() }
    var prev by remember(key) { mutableStateOf<List<LocalSet>>(emptyList()) }
    var restTotal by remember { mutableIntStateOf(90) }
    var restStart by remember { mutableLongStateOf(0L) }
    var prTrigger by remember { mutableIntStateOf(0) }

    // Seed once when Room delivers: today's saved sets come back as done rows, then open rows to at least 3.
    LaunchedEffect(key, history != null) {
        val h = history ?: return@LaunchedEffect
        if (rows.isNotEmpty()) return@LaunchedEffect
        val now = System.currentTimeMillis(); val tz = TimeZone.getDefault()
        val before = previousSets(h, key, now, tz)
        prev = before
        setsToday(h, key, now, tz).forEach { rows += SetRow(it.loadKg ?: 0.0, it.repCount, it.rpe, true).also { r -> r.score = rowScore(it.formscore, it.repCount, it.syncFailed) } }
        while (rows.size < 3) {
            val g = before.getOrNull(rows.size) ?: before.lastOrNull()
            rows += SetRow(g?.loadKg ?: rows.lastOrNull()?.kg ?: kg0, g?.repCount ?: rows.lastOrNull()?.reps ?: 8, null, false)
        }
    }

    val current = rows.firstOrNull { !it.done } ?: rows.lastOrNull()

    Column(
        Modifier.fillMaxSize().background(SahiColors.Ink).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("< TRAIN", Modifier.heightIn(min = 48.dp).clickable(onClick = onBack).wrapContentHeight(Alignment.CenterVertically), style = SahiType.label.copy(color = SahiColors.Dust))
                Text(label, style = SahiType.display.copy(color = SahiColors.Chalk))
                recoveringChip(primaryOf(heatOf(key, "")), recovery)?.let { RecoveryChip(it, Modifier.padding(top = 4.dp)) } // warn only
            }
        }

        if (showsPlates(key)) ChamferCard(Modifier.fillMaxWidth()) { // barbell lifts only (see PLATE_LIFTS)
            Text("PLATES", style = SahiType.label.copy(color = SahiColors.Dust))
            PlateStack(((current?.kg ?: 20.0)).toFloat(), modifier = Modifier.fillMaxWidth().height(96.dp).padding(vertical = 4.dp))
            Text(plateText(current?.kg ?: 20.0), style = SahiType.body.copy(color = SahiColors.Chalk))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("REST", style = SahiType.label.copy(color = SahiColors.Dust))
            REST_CHOICES.forEach { s ->
                val sel = s == restTotal
                Text(
                    "${s}s", Modifier.clip(CircleShape).background(if (sel) SahiColors.Haldi else SahiColors.Slate2)
                        .clickable { restTotal = s }.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = SahiType.label.copy(color = if (sel) SahiColors.Ink else SahiColors.Chalk),
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { PrMedal(prTrigger) }
        }
        RestBar(restStart, restTotal)

        rows.forEachIndexed { i, row ->
            SetRowView(i, row, prev.getOrNull(i), showKg) {
                val h = history.orEmpty()
                val pr = isPr(h, key, row.kg.takeIf { it > 0 }, row.reps)
                row.done = true
                scope.launch {
                    // Manual sets carry load (lifts only) and RPE.
                    save(row.reps, row.kg.takeIf { showKg && it > 0 }, row.rpe)
                }
                row.pings++
                restStart = System.currentTimeMillis()
                if (pr) prTrigger++
            }
        }
        SahiButton("+ Add set", {
            val last = rows.lastOrNull()
            rows += SetRow(last?.kg ?: kg0, last?.reps ?: 8, last?.rpe, false)
        })
    }
}

@Composable
private fun SetRowView(index: Int, row: SetRow, ghost: LocalSet?, showKg: Boolean, onDone: () -> Unit) {
    val motion = LocalMotion.current
    val pop = remember { Animatable(1f) }
    LaunchedEffect(row.pings) {
        if (row.pings == 0) return@LaunchedEffect
        pop.snapTo(0.7f)
        pop.animateTo(1f, motion.snapSpring())
    }
    // One left edge and one width for all three steppers (spread evenly); the check sits at the end of the first row.
    ChamferCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${index + 1}", Modifier.width(18.dp), style = SahiType.title.copy(color = if (row.done) SahiColors.Sahi else SahiColors.Dust))
            if (showKg) Stepper(kgText(row.kg), "kg", !row.done, { row.kg = stepKg(row.kg, -1) }, { row.kg = stepKg(row.kg, 1) }, Modifier.weight(1f))
            else Stepper(row.reps.toString(), "reps", !row.done, { row.reps = stepReps(row.reps, -1) }, { row.reps = stepReps(row.reps, 1) }, Modifier.weight(1f))
            Box(
                Modifier.size(48.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape)
                    .background(if (row.done) SahiColors.Sahi else SahiColors.Slate2)
                    .clickable(enabled = !row.done, onClick = onDone),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(48.dp)) { // centered check inside the 48 dp disc
                    val c = if (row.done) SahiColors.Ink else SahiColors.Dust; val u = size.width / 48f
                    val tick = Path().apply { moveTo(15f * u, 25f * u); lineTo(21.5f * u, 31.5f * u); lineTo(33f * u, 17f * u) }
                    drawPath(tick, c, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        if (showKg) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.width(18.dp))
            Stepper(row.reps.toString(), "reps", !row.done, { row.reps = stepReps(row.reps, -1) }, { row.reps = stepReps(row.reps, 1) }, Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.width(18.dp))
            Stepper(row.rpe?.let { String.format(Locale.US, "%.1f", it) } ?: "-", "rpe", !row.done,
                { row.rpe = stepRpe(row.rpe, -1) }, { row.rpe = stepRpe(row.rpe, 1) }, Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        Text(
            (row.score?.let { "Score $it ${scoreBand(it).label}   " } ?: "") +
                (ghost?.let { if (showKg) "Pehle: ${it.loadKg?.let(::kgText) ?: "-"} kg x ${it.repCount}" else "Pehle: ${it.repCount} reps" } ?: "Pehle: -"),
            Modifier.padding(start = 26.dp, top = 2.dp), style = SahiType.micro.copy(color = SahiColors.Dust),
        )
    }
}

@Composable
private fun Stepper(value: String, unit: String, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        StepBtn("-", enabled, onMinus)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = SahiType.title.copy(color = SahiColors.Chalk))
            Text(unit, style = SahiType.micro.copy(color = SahiColors.Dust))
        }
        StepBtn("+", enabled, onPlus)
    }
}

/** Visible 40 dp disc inside a 48 dp hit box. */
@Composable
private fun StepBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(SahiColors.Slate2), contentAlignment = Alignment.Center) {
            Text(label, style = SahiType.title.copy(color = if (enabled) SahiColors.Haldi else SahiColors.Hairline))
        }
    }
}

/** Linear bone that draws down as the rest runs out. Ticks 10x/s at FULL motion, 1x/s otherwise; only this composable recomposes. */
@Composable
private fun RestBar(startMs: Long, totalSec: Int) {
    val motion = LocalMotion.current
    var elapsed by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(startMs, totalSec) {
        if (startMs == 0L) return@LaunchedEffect
        while (true) {
            elapsed = System.currentTimeMillis() - startMs
            if (elapsed >= totalSec * 1000L) break
            delay(if (motion.level == MotionLevel.FULL) 100 else 1000)
        }
    }
    if (startMs == 0L) return
    val frac = restFraction(totalSec, elapsed)
    val left = restSecondsLeft(totalSec, elapsed)
    Column {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val y = size.height / 2; val r = 4.dp.toPx()
            drawLine(SahiColors.Hairline, Offset(r, y), Offset(size.width - r, y), 2.dp.toPx(), StrokeCap.Round)
            val x = r + (size.width - 2 * r) * frac
            drawLine(SahiColors.Haldi, Offset(r, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(SahiColors.Chalk, r, Offset(x, y))
        }
        Text(
            if (left > 0) String.format(Locale.US, "Rest %d:%02d", left / 60, left % 60) else "Ready - ek aur",
            Modifier.padding(top = 4.dp), style = SahiType.label.copy(color = if (left > 0) SahiColors.Dust else SahiColors.Sahi),
        )
    }
}

/** One coin spin (rotationY) plus "PR!" in Mehendi per [trigger] change; fades only when motion is not FULL. */
@Composable
fun PrMedal(trigger: Int) {
    val motion = LocalMotion.current
    val density = LocalDensity.current.density
    val rot = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        rot.snapTo(0f); alpha.snapTo(1f)
        if (motion.level == MotionLevel.FULL) rot.animateTo(720f, tween(900, easing = FastOutSlowInEasing))
        delay(1600)
        alpha.animateTo(0f, motion.state())
    }
    if (trigger == 0) return
    Row(Modifier.graphicsLayer { this.alpha = alpha.value }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).graphicsLayer { rotationY = rot.value; cameraDistance = 12f * density }
                .clip(CircleShape).background(SahiColors.Mehendi),
            contentAlignment = Alignment.Center,
        ) { Text("PR", style = SahiType.label.copy(color = SahiColors.Ink)) }
        Text("PR!", Modifier.padding(start = 8.dp), style = SahiType.title.copy(color = SahiColors.Mehendi))
    }
}
