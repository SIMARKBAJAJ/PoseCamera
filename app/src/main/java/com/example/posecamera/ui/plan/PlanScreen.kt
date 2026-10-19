package com.example.posecamera.ui.plan

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.CatalogPlan
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.sync.dayString
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.PlumbDivider
import com.example.posecamera.ui.design.SahiButton
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.home.prettyName
import java.util.TimeZone
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun PlanScreen(repo: LocalRepository, onBack: () -> Unit, onTrain: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val userPlan by remember(repo) { repo.userPlan() }.collectAsStateWithLifecycle(null)
    val slug = userPlan?.slug?.takeIf { it.isNotEmpty() }
    val plan by remember(repo, slug) { slug?.let(repo::catalogPlan) ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    val plans by remember(repo) { repo.catalogPlans() }.collectAsStateWithLifecycle(emptyList())
    val completions by remember(repo) { repo.planCompletions() }.collectAsStateWithLifecycle(emptyList())
    val todayIso = dayString(System.currentTimeMillis(), TimeZone.getDefault())

    // Only completions since this plan was assigned count (a re-picked plan starts fresh).
    val last = completions.filter { it.date >= (userPlan?.startDate ?: "") }.maxByOrNull { it.date }
    val names = remember(plan) { plan?.let { dayNamesOf(it.json) }.orEmpty() }
    val states = remember(names, last) { planDayStates(names.size, last?.dayIndex, last?.date == todayIso) }

    Column(
        Modifier.fillMaxSize().background(SahiColors.Ink).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("< BACK", Modifier.clickable(onClick = onBack), style = SahiType.label.copy(color = SahiColors.Dust))
        Text("Plan", style = SahiType.display.copy(color = SahiColors.Chalk))

        if (slug != null) ChamferCard(Modifier.fillMaxWidth()) {
            Text(plan?.name ?: prettyName(slug), style = SahiType.title.copy(color = SahiColors.Chalk))
            plan?.let { Text(planMeta(it), style = SahiType.body.copy(color = SahiColors.Dust)) }
            PlumbDivider(Modifier.padding(vertical = 8.dp))
            if (names.isEmpty()) Text("Plan ke din abhi download nahi hue.", style = SahiType.body.copy(color = SahiColors.Dust))
            names.forEachIndexed { i, name ->
                RoadRow(name, states[i], i == names.lastIndex, i) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SahiButton("Train", onTrain)
                        SahiButton("Done", { scope.launch { repo.completeDay(i, todayIso) } })
                    }
                }
            }
        } else ChamferCard(Modifier.fillMaxWidth()) {
            Text("Koi plan nahi", style = SahiType.title.copy(color = SahiColors.Chalk))
            Text("Neeche se ek plan chuno, phir har din bas follow karo.", style = SahiType.body.copy(color = SahiColors.Dust))
        }

        ChamferCard(Modifier.fillMaxWidth()) {
            Text("PICK A PLAN", style = SahiType.label.copy(color = SahiColors.Dust))
            PlumbDivider(Modifier.padding(vertical = 8.dp))
            if (plans.isEmpty()) Text("Plans download ho rahe hain. Sync ke baad aayenge.", style = SahiType.body.copy(color = SahiColors.Dust))
            plans.forEach { p ->
                val chosen = p.slug == slug
                Column(
                    Modifier.fillMaxWidth().clickable(enabled = !chosen) { scope.launch { repo.assignPlan(p.slug, todayIso, null) } }.padding(vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f), style = SahiType.body.copy(color = SahiColors.Chalk))
                        Text(if (chosen) "ACTIVE" else "CHUNO", style = SahiType.label.copy(color = if (chosen) SahiColors.Sahi else SahiColors.Haldi))
                    }
                    Text(planMeta(p), style = SahiType.micro.copy(color = SahiColors.Dust))
                }
            }
        }
    }
}

private fun planMeta(p: CatalogPlan) =
    listOfNotNull(p.level, "${p.daysPerWeek} days/week", p.split?.replace('_', ' ')).joinToString(" - ")

/** One day on the road: node on the plumb line at the left (DONE = Sahi joint + solid bone, TODAY = pulsing Haldi, FUTURE = dim), name at the right. */
@Composable
private fun RoadRow(name: String, state: DayState, isLast: Boolean, index: Int, todayActions: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val ping: State<Float> =
        if (state == DayState.TODAY && motion.allowIdleLoops) {
            rememberInfiniteTransition(label = "road").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "ping")
        } else remember { mutableFloatStateOf(0f) }
    Row(Modifier.fillMaxWidth().height(if (state == DayState.TODAY) 112.dp else 64.dp)) {
        Canvas(Modifier.width(40.dp).fillMaxSize()) {
            val x = size.width / 2; val y = 20.dp.toPx(); val r = 7.dp.toPx(); val w = 2.dp.toPx()
            if (!isLast) drawLine(if (state == DayState.DONE) SahiColors.Sahi else SahiColors.Hairline, Offset(x, y), Offset(x, size.height), w)
            when (state) {
                DayState.DONE -> drawCircle(SahiColors.Sahi, r, Offset(x, y))
                DayState.TODAY -> {
                    drawCircle(SahiColors.Haldi, r, Offset(x, y))
                    val t = ping.value
                    if (motion.allowIdleLoops) drawCircle(SahiColors.Haldi.copy(alpha = 1f - t), r * (1.2f + 1.4f * t), Offset(x, y), style = Stroke(2.dp.toPx()))
                    else drawCircle(SahiColors.Haldi.copy(alpha = 0.5f), r * 1.8f, Offset(x, y), style = Stroke(2.dp.toPx()))
                }
                DayState.FUTURE -> drawCircle(SahiColors.Hairline, r, Offset(x, y), style = Stroke(w))
            }
        }
        Column(Modifier.weight(1f)) {
            Text("DAY ${index + 1}", style = SahiType.micro.copy(color = if (state == DayState.FUTURE) SahiColors.Hairline else SahiColors.Dust))
            Text(
                name, style = SahiType.body.copy(color = when (state) {
                    DayState.FUTURE -> SahiColors.Dust
                    else -> SahiColors.Chalk
                }),
            )
            if (state == DayState.TODAY) todayActions()
        }
    }
}
