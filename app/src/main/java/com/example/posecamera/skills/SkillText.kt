package com.example.posecamera.skills

import com.example.posecamera.data.LocalSet
import java.util.TimeZone

// Pure text/summary helpers for the skill UI. All targets are UNVERIFIED PROJECT DEFAULTS (see SkillTree.kt).

private fun num(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

/** '3 sets of 8 at RPE 8 or easier, on 2 different days' (seconds for holds, 'per side' where it applies). */
fun criterionText(s: Step): String {
    val unit = if (s.metric == Metric.SECONDS) " s" else ""
    val side = if (s.perSide) " per side" else ""
    return "${s.sets} sets of ${s.target}$unit$side at RPE ${num(s.maxRpe)} or easier, on ${s.sessions} different days"
}

/** 'best 6 / 8, 1 / 2 days' or 'no sets yet'. */
fun progressText(s: Step, st: StepStatus): String {
    val best = st.lastBest ?: return "no sets yet"
    return "best $best / ${s.target}, ${minOf(st.qualifyingDays, s.sessions)} / ${s.sessions} days"
}

/** State in words; Sahi green is never implied (it means correct form only). */
fun stateText(s: Step, all: Map<String, StepStatus>, isNext: Boolean): String {
    val st = all.getValue(s.id)
    return when (st.state) {
        StepState.ACHIEVED -> if (st.implied) "Done (implied)" else "Done"
        StepState.OPEN -> if (isNext) "Next up" else "Open"
        StepState.LOCKED -> {
            val unmet = s.requires.filter { all[it]?.state.let { x -> x != StepState.ACHIEVED && x != StepState.ADVISORY } }
                .mapNotNull { SKILL_BY_ID[it]?.name }
            "Locked: needs " + unmet.joinToString(", ")
        }
        StepState.ADVISORY -> if (!s.inCatalog) "Not in our library yet" else "Hold: tracking soon"
    }
}

fun cameraNote(s: Step) = when {
    s.hold || !s.inCatalog -> "Not logged yet" // Phase 1: holds and steps missing from the catalog cannot be logged
    s.poseTrackable -> "Camera counts this"
    else -> "Log by hand"
}

fun plateauText(p: Plateau): String? = when (p) {
    Plateau.None -> null
    is Plateau.Regress -> "Plateau: try ${p.to.name} to rebuild"
    Plateau.Deload -> "Plateau: try an easier week, fewer sets"
}

class NextUpInfo(val step: Step, val status: StepStatus, val plateau: Plateau)

/** Recency-based suggestion from the logged sets; null = nothing loggable is open. */
fun nextUpInfo(sets: List<LocalSet>, nowMs: Long, tz: TimeZone): NextUpInfo? {
    val ev = evidenceOf(sets)
    val st = statusOf(SKILL_STEPS, ev, emptySet(), ::canLogPhase1, tz)
    val n = nextUp(SKILL_STEPS, st, ev, ::canLogPhase1) ?: return null
    return NextUpInfo(n, st.getValue(n.id), plateauOf(n, ev[n.id].orEmpty(), st, nowMs, tz))
}
