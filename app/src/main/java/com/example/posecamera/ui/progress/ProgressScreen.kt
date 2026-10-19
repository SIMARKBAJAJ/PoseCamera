package com.example.posecamera.ui.progress

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.data.LocalWeight
import com.example.posecamera.sync.dayString
import com.example.posecamera.sync.streakOf
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.PlumbDivider
import com.example.posecamera.ui.design.SahiButton
import com.example.posecamera.ui.design.SahiChip
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiSheet
import com.example.posecamera.ui.design.SahiStepper
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.design.StatBlock
import com.example.posecamera.ui.design.g3d.MuscleMap
import com.example.posecamera.ui.design.g3d.MuscleSide
import com.example.posecamera.ui.home.heatCaption
import com.example.posecamera.ui.home.muscleHeat
import com.example.posecamera.ui.home.plural
import com.example.posecamera.ui.home.prettyName
import com.example.posecamera.ui.home.validSets
import com.example.posecamera.ui.nutrition.MacroRing
import com.example.posecamera.ui.nutrition.targetsOf
import com.example.posecamera.ui.nutrition.totalsOf
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlinx.coroutines.launch

private fun kgFmt(v: Double) = String.format(Locale.US, "%.1f", v)

@Composable
fun ProgressScreen(repo: LocalRepository, onOpenNutrition: () -> Unit, onTrain: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sets by remember(repo) { repo.recentSets(1000) }.collectAsStateWithLifecycle(emptyList())
    val weights by remember(repo) { repo.recentWeights() }.collectAsStateWithLifecycle(emptyList())
    val profile by remember(repo) { repo.profile() }.collectAsStateWithLifecycle(null)
    val tz = TimeZone.getDefault()
    val now = System.currentTimeMillis()
    val todayIso = dayString(now, tz)
    val foods by remember(repo, todayIso) { repo.foodLogs(todayIso) }.collectAsStateWithLifecycle(emptyList())
    var range by rememberSaveable { mutableStateOf(30) }
    var logging by rememberSaveable { mutableStateOf(false) }
    var side by rememberSaveable { mutableStateOf(MuscleSide.FRONT) }

    val streak = remember(sets, now / 60_000) { streakOf(validSets(sets).map { it.timestamp }, now, tz) }
    val window = remember(weights, range, now / 60_000) { weightWindow(weights, now, tz, range) }
    val prs = remember(sets) { prBests(sets) }
    val volume = remember(sets, now / 60_000) { weeklyVolume(sets, now) }
    val heat = remember(sets, now / 60_000) { muscleHeat(sets, now, 30) }
    val targets = remember(profile) { targetsOf(profile, Calendar.getInstance().get(Calendar.YEAR)) }
    val eaten = remember(foods) { totalsOf(foods) }

    Box(Modifier.fillMaxSize().background(SahiColors.Ink)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Progress", style = SahiType.display.copy(color = SahiColors.Chalk))

            ChamferCard(Modifier.fillMaxWidth()) {
                Text("STREAK", style = SahiType.label.copy(color = SahiColors.Dust))
                StatBlock(streak.toFloat(), if (streak == 1) "day" else "days")
                if (sets.isEmpty()) {
                    Text("Abhi koi set nahi. Pehla set karo, streak yahin banegi.", style = SahiType.body.copy(color = SahiColors.Dust))
                    SahiButton("Pehla set karo", onTrain, Modifier.padding(top = 12.dp))
                } else Text("${plural(validSets(sets).size, "set")} logged", style = SahiType.body.copy(color = SahiColors.Dust))
            }

            ChamferCard(Modifier.fillMaxWidth().clickable(onClick = onOpenNutrition)) {
                Text("AAJ KA KHANA", style = SahiType.label.copy(color = SahiColors.Dust))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MacroRing("KCAL", eaten.kcal, targets.kcal, "", Modifier.weight(1f))
                    MacroRing("PROTEIN", eaten.proteinG, targets.proteinG, "g", Modifier.weight(1f))
                    MacroRing("CARBS", eaten.carbsG, targets.carbsG, "g", Modifier.weight(1f))
                    MacroRing("FAT", eaten.fatG, targets.fatG, "g", Modifier.weight(1f))
                }
                Text(
                    if (foods.isEmpty()) "Aaj kuch log nahi hua - khana add karo >" else "Khana dekho / add karo >",
                    Modifier.padding(top = 8.dp), style = SahiType.label.copy(color = SahiColors.Haldi),
                )
            }

            ChamferCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WEIGHT", Modifier.weight(1f), style = SahiType.label.copy(color = SahiColors.Dust))
                    SahiChip("30d", range == 30, { range = 30 })
                    SahiChip("90d", range == 90, { range = 90 })
                }
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                if (window.isEmpty()) {
                    Text("Is range mein weight nahi", style = SahiType.title.copy(color = SahiColors.Chalk))
                } else {
                    Text("${kgFmt(window.last().kg)} kg", style = SahiType.display.copy(color = SahiColors.Chalk))
                    if (window.size > 1) {
                        val d = window.last().kg - window.first().kg
                        Text(
                            "${if (d >= 0) "+" else ""}${kgFmt(d)} kg in ${range}d", style = SahiType.label.copy(color = SahiColors.Dust),
                        )
                    }
                    WeightChart(window, range, Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp))
                }
                SahiButton("+ Log weight", { logging = true }, Modifier.padding(top = 12.dp))
            }

            ChamferCard(Modifier.fillMaxWidth()) {
                Text("PERSONAL RECORDS", style = SahiType.label.copy(color = SahiColors.Dust))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                if (prs.isEmpty()) {
                    Text("Pehla PR banao", style = SahiType.title.copy(color = SahiColors.Chalk))
                    SahiButton("Train karo", onTrain, Modifier.padding(top = 12.dp))
                }
                prs.forEachIndexed { i, pr ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(if (i == 0) SahiColors.Mehendi else SahiColors.Slate2),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (i == 0) "PR" else "${i + 1}", style = SahiType.micro.copy(color = if (i == 0) SahiColors.Ink else SahiColors.Dust)) } // rank, so no row shows an empty circle
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(prettyName(pr.key), style = SahiType.body.copy(color = SahiColors.Chalk))
                            Text(dayString(pr.timestamp, tz), style = SahiType.micro.copy(color = SahiColors.Dust))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                pr.loadKg?.let { "${kgFmt(it)} kg x ${pr.reps}" } ?: plural(pr.reps, "rep"),
                                style = SahiType.body.copy(color = SahiColors.Haldi),
                            )
                            pr.e1rm?.let { Text("1RM ~ ${kgFmt(it)} kg", style = SahiType.micro.copy(color = SahiColors.Dust)) }
                        }
                    }
                }
            }

            ChamferCard(Modifier.fillMaxWidth()) {
                Text("WEEKLY VOLUME", style = SahiType.label.copy(color = SahiColors.Dust))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                if (volume.all { it == 0.0 }) {
                    Text("Loaded sets log karo, volume yahan dikhega", style = SahiType.body.copy(color = SahiColors.Dust))
                } else {
                    VolumeBars(volume, Modifier.fillMaxWidth().height(120.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        volume.indices.forEach { i ->
                            val back = volume.lastIndex - i
                            Text(if (back == 0) "This" else "W-$back", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = SahiType.micro.copy(color = SahiColors.Dust))
                        }
                    }
                    Text("kg x reps, last ${volume.size} weeks (right = this week)", style = SahiType.micro.copy(color = SahiColors.Dust))
                }
            }

            ChamferCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MUSCLES - LAST 30 DAYS", Modifier.weight(1f), style = SahiType.label.copy(color = SahiColors.Dust))
                    SahiChip(if (side == MuscleSide.FRONT) "Front" else "Back", false, {
                        side = if (side == MuscleSide.FRONT) MuscleSide.BACK else MuscleSide.FRONT
                    })
                }
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                MuscleMap(heat, side, Modifier.fillMaxWidth().height(240.dp))
                val caption = heatCaption(heat)
                if (caption != null) Text(caption, Modifier.padding(top = 8.dp), style = SahiType.title.copy(color = SahiColors.Chalk))
                else {
                    Text("Koi muscle data nahi", Modifier.padding(top = 8.dp), style = SahiType.title.copy(color = SahiColors.Chalk))
                    SahiButton("Pehla set karo", onTrain, Modifier.padding(top = 12.dp))
                }
            }
        }
        if (logging) {
            var kg by remember { mutableDoubleStateOf(weights.firstOrNull()?.kg ?: profile?.weightKg ?: 70.0) }
            SahiSheet(onDismiss = { logging = false }) {
                Text("Aaj ka weight", style = SahiType.title.copy(color = SahiColors.Chalk))
                SahiStepper(kgFmt(kg), "kg", { kg = stepWeight(kg, -1) }, { kg = stepWeight(kg, 1) }, Modifier.align(Alignment.CenterHorizontally))
                SahiButton("Save", { scope.launch { repo.logWeight(kg, todayIso); logging = false } }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Line draws on (0 -> 1 along its length) whenever the data or range changes; instant when motion is OFF. */
@Composable
private fun WeightChart(points: List<LocalWeight>, range: Int, modifier: Modifier) {
    val motion = LocalMotion.current
    val p = remember { Animatable(1f) }
    LaunchedEffect(points, range) {
        if (motion.level == MotionLevel.OFF) p.snapTo(1f) else { p.snapTo(0f); p.animateTo(1f, motion.screen()) }
    }
    Canvas(modifier) {
        val pad = 8.dp.toPx()
        val lo = points.minOf { it.kg }; val hi = points.maxOf { it.kg }
        val span = max(hi - lo, 1.0)
        val d0 = epochDay(points.first().date); val dSpan = max(epochDay(points.last().date) - d0, 1L)
        val pts = points.map {
            val x = if (points.size == 1) size.width / 2 else pad + (size.width - 2 * pad) * ((epochDay(it.date) - d0).toFloat() / dSpan)
            Offset(x, pad + (size.height - 2 * pad) * (1f - ((it.kg - lo) / span).toFloat()))
        }
        for (i in 0..2) { // faint grid
            val y = pad + (size.height - 2 * pad) * i / 2f
            drawLine(SahiColors.Hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        val path = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        val shown = Path()
        val pm = PathMeasure().apply { setPath(path, false) }
        pm.getSegment(0f, pm.length * p.value, shown, true)
        drawPath(shown, SahiColors.Haldi, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val upTo = (pts.size * p.value).toInt().coerceAtMost(pts.size)
        for (i in 0 until upTo) drawCircle(if (i == pts.lastIndex) SahiColors.Chalk else SahiColors.Haldi, if (i == pts.lastIndex) 5.dp.toPx() else 3.dp.toPx(), pts[i])
    }
}

@Composable
private fun VolumeBars(values: List<Double>, modifier: Modifier) {
    val motion = LocalMotion.current
    val p = remember { Animatable(if (motion.level == MotionLevel.OFF) 1f else 0f) }
    LaunchedEffect(values) { p.animateTo(1f, motion.screen()) }
    Canvas(modifier) {
        val top = values.max().coerceAtLeast(1.0)
        val slot = size.width / values.size; val bw = slot * 0.5f
        values.forEachIndexed { i, v ->
            val h = (size.height * (v / top).toFloat()) * p.value
            val x = slot * i + (slot - bw) / 2
            drawRect(if (i == values.lastIndex) SahiColors.Haldi else SahiColors.Hairline, Offset(x, size.height - h), androidx.compose.ui.geometry.Size(bw, h))
        }
    }
}
