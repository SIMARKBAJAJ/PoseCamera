package com.example.posecamera.ui.skills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.skills.Family
import com.example.posecamera.skills.SKILL_STEPS
import com.example.posecamera.skills.StepState
import com.example.posecamera.skills.cameraNote
import com.example.posecamera.skills.canLogPhase1
import com.example.posecamera.skills.criterionText
import com.example.posecamera.skills.evidenceOf
import com.example.posecamera.skills.nextUp
import com.example.posecamera.skills.plateauOf
import com.example.posecamera.skills.plateauText
import com.example.posecamera.skills.progressText
import com.example.posecamera.skills.stateText
import com.example.posecamera.skills.statusOf
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.PlumbDivider
import com.example.posecamera.ui.design.SahiButton
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import java.util.TimeZone

/** Skill progressions as a list grouped by family (the 3D map comes later). [onLog] receives a skill step id. */
@Composable
fun SkillTreeScreen(repo: LocalRepository, onBack: () -> Unit, onLog: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val sets by remember(repo) { repo.skillSets() }.collectAsStateWithLifecycle(emptyList())
    val now = System.currentTimeMillis()
    val tz = TimeZone.getDefault()
    val derived = remember(sets, now / 60_000) {
        val ev = evidenceOf(sets)
        val st = statusOf(SKILL_STEPS, ev, emptySet(), ::canLogPhase1, tz)
        Triple(ev, st, nextUp(SKILL_STEPS, st, ev, ::canLogPhase1))
    }
    val (ev, st, next) = derived

    Column(
        Modifier.fillMaxSize().background(SahiColors.Ink).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "< BACK", Modifier.heightIn(min = 48.dp).clickable(onClick = onBack).wrapContentHeight(Alignment.CenterVertically),
            style = SahiType.label.copy(color = SahiColors.Dust),
        )
        Text("Skills", style = SahiType.display.copy(color = SahiColors.Chalk))
        ChamferCard(Modifier.fillMaxWidth()) {
            Text(
                "Targets are SahiRep project defaults, not validated. The camera counts only push-up, squat, pull-up and plank. " +
                    "Not medical advice. Stop if something hurts.",
                style = SahiType.body.copy(color = SahiColors.Dust),
            )
        }
        Family.entries.forEach { fam ->
            ChamferCard(Modifier.fillMaxWidth()) {
                Text(fam.label.uppercase(), style = SahiType.label.copy(color = SahiColors.Dust))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                SKILL_STEPS.filter { it.family == fam }.forEach { s ->
                    val status = st.getValue(s.id)
                    val isNext = next?.id == s.id
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(s.name, style = SahiType.title.copy(color = SahiColors.Chalk))
                        Text(
                            stateText(s, st, isNext),
                            style = SahiType.label.copy(color = if (isNext) SahiColors.Haldi else SahiColors.Dust), // never Sahi green: it means correct form
                        )
                        Text(criterionText(s), style = SahiType.micro.copy(color = SahiColors.Dust))
                        Text(progressText(s, status) + "   " + cameraNote(s), style = SahiType.micro.copy(color = SahiColors.Dust))
                        if (status.state == StepState.OPEN) {
                            plateauText(plateauOf(s, ev[s.id].orEmpty(), st, now, tz))?.let {
                                Text(it, style = SahiType.micro.copy(color = SahiColors.Dust))
                            }
                            if (canLogPhase1(s)) SahiButton("Log it", { onLog(s.id) }, Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
