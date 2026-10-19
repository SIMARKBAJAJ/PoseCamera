package com.example.posecamera.ui.train

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.posecamera.lifts.LiftAnalysis
import com.example.posecamera.pose.ExerciseAnalysis
import com.example.posecamera.pose.ExerciseConfig
import com.example.posecamera.pose.ExerciseType
import com.example.posecamera.pose.RepSetProgress
import com.example.posecamera.ui.design.DepthGauge
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.PlumbLine
import com.example.posecamera.ui.design.RepRing
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType

/** No set target exists in the camera flow yet, so the ring fills toward this many reps (plank: seconds). */
const val HUD_TARGET_REPS = 10
const val HUD_TARGET_HOLD_S = 60

class HudState(
    val count: Int,
    val target: Int,
    val depth: Float,
    val score: Float?,
    val band: FormBand?,
    val deviation: Float,
    val cues: List<String>,
    val notice: String?,
)

/** Reads only what the pose layer already exposes; adds no detection. [progress] is RepCounter.progress() for squat/push-up. */
fun buildHud(analysis: ExerciseAnalysis?, config: ExerciseConfig, progress: RepSetProgress?): HudState {
    val lift = analysis as? LiftAnalysis
    val plank = config.type == ExerciseType.PLANK
    val count = if (lift != null) (if (plank) lift.holdSeconds else lift.reps) else progress?.attemptedReps ?: 0
    val score = progress?.takeIf { lift == null && it.attemptedReps > 0 }?.let { it.cleanReps * 100f / it.attemptedReps }
    val band = score?.let(::formBand) ?: analysis?.state?.let(::bandOf)
    return HudState(
        count = count,
        target = if (plank) HUD_TARGET_HOLD_S else HUD_TARGET_REPS,
        depth = if (plank) 0f else depthOf(analysis?.movementAngleDegrees, config.repRules),
        score = score,
        band = band,
        deviation = deviationOf(analysis),
        cues = lift?.lastRep?.faults?.take(2)?.map { it.cue }.orEmpty(),
        notice = lift?.notice,
    )
}

private fun FormBand.color(): Color = when (this) {
    FormBand.SAHI -> SahiColors.Sahi
    FormBand.HALDI -> SahiColors.Haldi
    FormBand.SINDOOR -> SahiColors.Sindoor
}

/** The four HUD elements: rep ring, depth gauge (left), plumb line (right edge), form chip + fault cues (bottom). */
@Composable
fun BoxScope.FormHud(hud: HudState) {
    val motion = LocalMotion.current
    val scrim = SahiColors.Ink.copy(alpha = 0.5f)
    Column(Modifier.align(Alignment.CenterStart).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(80.dp).background(scrim, CircleShape), contentAlignment = Alignment.Center) {
            RepRing(hud.count / hud.target.toFloat(), Modifier.size(80.dp))
            Text(hud.count.toString(), style = SahiType.display.copy(color = SahiColors.Chalk))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DepthGauge(hud.depth, Modifier.size(80.dp).background(scrim, CircleShape))
            Text("DEPTH", Modifier.background(scrim, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp), style = SahiType.micro.copy(color = SahiColors.Chalk))
        }
    }
    PlumbLine(hud.deviation, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(40.dp, 160.dp))
    Column(
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 150.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        hud.band?.let { b ->
            val c = b.color()
            Column(
                Modifier.then(if (motion.allowGlow) Modifier.drawBehind {
                    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.35f), Color.Transparent)), size.maxDimension * 0.8f)
                } else Modifier)
                    .background(scrim, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    hud.score?.let { "FORM ${it.toInt()}" } ?: b.name,
                    style = SahiType.title.copy(color = c),
                )
                hud.notice?.let { Text(it, Modifier.widthIn(max = 240.dp), textAlign = TextAlign.Center, style = SahiType.micro.copy(color = SahiColors.Chalk)) }
            }
        }
        AnimatedContent(
            targetState = hud.cues,
            transitionSpec = {
                if (motion.level == MotionLevel.OFF) EnterTransition.None togetherWith ExitTransition.None
                else (scaleIn(motion.snapSpring(), initialScale = 0.8f) + fadeIn(motion.micro())) togetherWith
                    (scaleOut(motion.micro(), targetScale = 0.8f) + fadeOut(motion.micro()))
            },
            label = "cues",
        ) { cues ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                cues.forEach { cue ->
                    Text(
                        cue, Modifier.widthIn(max = 280.dp).background(SahiColors.Sindoor.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        textAlign = TextAlign.Center, style = SahiType.label.copy(color = SahiColors.Chalk),
                    )
                }
            }
        }
    }
}
