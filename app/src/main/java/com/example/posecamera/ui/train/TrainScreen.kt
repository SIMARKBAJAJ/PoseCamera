package com.example.posecamera.ui.train

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.lifts.Lifts
import com.example.posecamera.skills.SKILL_BY_ID
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.PlumbDivider
import com.example.posecamera.ui.design.SahiButton
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.home.prettyName
import com.example.posecamera.ui.home.readyNames
import com.example.posecamera.ui.home.rememberRecovery
import com.example.posecamera.ui.library.CAMERA_ONLY
import kotlinx.coroutines.flow.flowOf

/**
 * Train tab. [selected] (an ExerciseType name) and [coaching] are hoisted into AppShell so switching tabs keeps them.
 * [camera] is the existing permission gate + camera flow, composed only while coaching.
 */
@Composable
fun TrainScreen(
    repo: LocalRepository,
    selected: String?,
    onSelect: (String?) -> Unit,
    coaching: Boolean,
    onCoaching: (Boolean) -> Unit,
    onOpenPlan: () -> Unit,
    camera: @Composable (onDemos: (Boolean) -> Unit, startType: String?) -> Unit,
) {
    // Squat / Push-up have no workout-log lift: selecting them opens the Form coach with that exercise pre-selected.
    val camType = selected?.takeIf { it in CAMERA_ONLY }
    if (coaching || camType != null) {
        val close = { onCoaching(false); if (camType != null) onSelect(null) }
        BackHandler { close() }
        var demos by remember { mutableStateOf(false) } // Demos has its own Back; CLOSE would cover its cues
        Box(Modifier.fillMaxSize()) {
            camera({ demos = it }, camType)
            if (!demos)
            Text(
                "CLOSE", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)
                    .clip(CircleShape).background(SahiColors.Ink.copy(alpha = 0.7f))
                    .clickable { close() }.heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 10.dp).wrapContentHeight(Alignment.CenterVertically),
                style = SahiType.label.copy(color = SahiColors.Chalk),
            )
        }
        return
    }
    // "slug:<stepId>" = a skill-tree step logged by hand (bodyweight, no load).
    val step = selected?.takeIf { it.startsWith("slug:") }?.let { SKILL_BY_ID[it.removePrefix("slug:")] }
    if (step != null) {
        BackHandler { onSelect(null) }
        WorkoutLogScreen(repo, step.id, step.name, false, onBack = { onSelect(null) }) { reps, _, rpe ->
            repo.saveManualSet(step.id, reps, null, rpe)
        }
        return
    }
    val config = selected?.let { name -> Lifts.all.firstOrNull { it.type.name == name } }
    if (config != null) {
        BackHandler { onSelect(null) }
        WorkoutLogScreen(repo, config, onBack = { onSelect(null) })
        return
    }

    val sets by remember(repo) { repo.recentSets() }.collectAsStateWithLifecycle(emptyList())
    val userPlan by remember(repo) { repo.userPlan() }.collectAsStateWithLifecycle(null)
    val planSlug = userPlan?.slug?.takeIf { it.isNotEmpty() }
    val plan by remember(repo, planSlug) { planSlug?.let(repo::catalogPlan) ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    val lifts = remember(sets) { orderedLifts(Lifts.all, sets) }
    val recovery = rememberRecovery(repo, sets)

    Column(
        Modifier.fillMaxSize().background(SahiColors.Ink).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Train", style = SahiType.display.copy(color = SahiColors.Chalk))
        readyNames(recovery).take(3).takeIf { it.isNotEmpty() }?.let {
            Text("Ready today: " + it.joinToString(", "), style = SahiType.body.copy(color = SahiColors.Sahi))
        }
        SahiButton("Form coach", { onCoaching(true) }, Modifier.fillMaxWidth().height(64.dp))
        if (planSlug == null) {
            Text("Plan chuno >", Modifier.clickable(onClick = onOpenPlan), style = SahiType.label.copy(color = SahiColors.Haldi))
        } else {
            ChamferCard(Modifier.fillMaxWidth()) {
                Text("TODAY'S PLAN", style = SahiType.label.copy(color = SahiColors.Dust))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                Text(plan?.name ?: prettyName(planSlug), style = SahiType.title.copy(color = SahiColors.Chalk))
                // Plan slots name a movement pattern, not an exercise (the server resolves them), so pick the lift below.
                Text("Neeche se lift chuno aur sets log karo.", style = SahiType.body.copy(color = SahiColors.Dust))
                SahiButton("Plan dekho", onOpenPlan, Modifier.padding(top = 12.dp))
            }
        }
        ChamferCard(Modifier.fillMaxWidth()) {
            Text("LIFTS", style = SahiType.label.copy(color = SahiColors.Dust))
            PlumbDivider(Modifier.padding(vertical = 8.dp))
            listOf("SQUAT" to "Squat (camera)", "PUSH_UP" to "Push-up (camera)").forEach { (type, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(type) }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, style = SahiType.body.copy(color = SahiColors.Chalk))
                    Text(">", style = SahiType.body.copy(color = SahiColors.Haldi))
                }
            }
            lifts.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(c.type.name) }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(c.type.displayLabel, style = SahiType.body.copy(color = SahiColors.Chalk))
                    Text(">", style = SahiType.body.copy(color = SahiColors.Haldi))
                }
            }
        }
    }
}
