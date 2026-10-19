package com.example.posecamera.skills

import com.example.posecamera.data.LocalSet
import com.example.posecamera.ui.home.validSets
import java.util.TimeZone

// Pure skill-tree derivations (docs/SKILL_TREE_DESIGN.md 3c). Every threshold lives in SkillTree.kt and is an
// UNVERIFIED PROJECT DEFAULT. java.time is avoided on purpose (minSdk 24, no desugaring).

private const val DAY_MS = 86_400_000L
private const val STALE_DAYS = 21
private const val DELOAD_RPE = 9.0

// Same bucket as ui.train's private dayOf (local day number).
private fun dayOf(ms: Long, tz: TimeZone) = Math.floorDiv(ms + tz.getOffset(ms), DAY_MS)

class Evidence(val stepId: String, val value: Int, val rpe: Double?, val ts: Long)

enum class StepState { LOCKED, OPEN, ACHIEVED, ADVISORY }

class StepStatus(
    val state: StepState,
    val achievedAt: Long?,
    val implied: Boolean,
    val qualifyingDays: Int,
    val lastBest: Int?,
    val selfMarked: Boolean,
)

sealed interface Plateau {
    data object None : Plateau
    data class Regress(val to: Step) : Plateau
    data object Deload : Plateau
}

/** Valid, non-warmup sets mapped to a step id (`exerciseSlug ?: CAMERA_SLUG[exerciseType]`); value = repCount (all reps). */
fun evidenceOf(sets: List<LocalSet>): Map<String, List<Evidence>> =
    validSets(sets).asSequence()
        .filter { it.setType != "warmup" }
        .mapNotNull { s ->
            val id = s.exerciseSlug ?: CAMERA_SLUG[s.exerciseType]
            if (id != null && id in SKILL_BY_ID) Evidence(id, s.repCount, s.rpe, s.timestamp) else null
        }
        .groupBy { it.stepId }

private fun Step.qualifies(e: Evidence) = e.value >= target && (e.rpe == null || e.rpe <= maxRpe)

/** Local-day -> qualifying evidence on that day, only days with at least `sets` of them, oldest day first. */
private fun qualifyingDays(step: Step, ev: List<Evidence>, tz: TimeZone): List<List<Evidence>> =
    ev.filter(step::qualifies).groupBy { dayOf(it.ts, tz) }.toSortedMap().values.filter { it.size >= step.sets }

fun statusOf(
    steps: List<Step>,
    ev: Map<String, List<Evidence>>,
    marked: Set<String>,
    canLog: (Step) -> Boolean,
    tz: TimeZone,
): Map<String, StepStatus> {
    val byId = steps.associateBy { it.id }
    val days = steps.associate { it.id to qualifyingDays(it, ev[it.id].orEmpty(), tz) }
    val direct = steps.filter { it.id in marked || days.getValue(it.id).size >= it.sessions }.map { it.id }.toSet()
    // Ancestors of any achieved step are achieved too (a pull-up doer is not told to do chin-ups).
    val implied = HashSet<String>()
    fun mark(id: String) { byId[id]?.requires?.forEach { if (it !in direct && implied.add(it)) mark(it) } }
    direct.forEach(::mark)

    val out = LinkedHashMap<String, StepStatus>()
    for (s in steps) {
        val d = days.getValue(s.id)
        val achieved = s.id in direct || s.id in implied
        val state = when {
            achieved -> StepState.ACHIEVED
            !canLog(s) -> StepState.ADVISORY
            s.requires.all { out[it]?.state.let { st -> st == StepState.ACHIEVED || st == StepState.ADVISORY } } -> StepState.OPEN
            else -> StepState.LOCKED
        }
        val at = if (s.id in direct && d.size >= s.sessions) d[s.sessions - 1].maxOf { it.ts } else null
        out[s.id] = StepStatus(state, at, s.id in implied, d.size, ev[s.id]?.maxOfOrNull { it.value }, s.id in marked)
    }
    return out
}

/** Longest-path depth (roots = 0) and a lane x per family; branches at one depth are spread 0.6 apart. */
fun layoutOf(steps: List<Step>): Map<String, Pair<Float, Int>> {
    val depth = HashMap<String, Int>()
    val taken = HashSet<Pair<Int, Int>>()
    val out = LinkedHashMap<String, Pair<Float, Int>>()
    for (s in steps) {
        val d = s.requires.maxOfOrNull { (depth[it] ?: 0) + 1 } ?: 0
        depth[s.id] = d
        val lane = when (s.family) { Family.PUSH -> -15; Family.LEGS -> -5; Family.PULL -> 5; Family.CORE -> 15 }
        var k = 0
        fun xAt(n: Int) = lane + (if (n % 2 == 1) (n + 1) / 2 else -(n / 2)) * 6 // 0, +6, -6, +12, -12 ... tenths
        while ((xAt(k) to d) in taken) k++
        taken += xAt(k) to d
        out[s.id] = xAt(k) / 10f to d
    }
    return out
}

/** Recency-based suggestion; null = nothing loggable is open ("Map complete for now"). Equipment is ignored. */
fun nextUp(
    steps: List<Step>,
    st: Map<String, StepStatus>,
    ev: Map<String, List<Evidence>>,
    canLog: (Step) -> Boolean,
): Step? {
    val layout = layoutOf(steps)
    val familyLast = steps.groupBy { it.family }.mapValues { (_, l) -> l.maxOfOrNull { s -> ev[s.id]?.maxOfOrNull { it.ts } ?: Long.MIN_VALUE } ?: Long.MIN_VALUE }
    val index = steps.withIndex().associate { it.value.id to it.index }
    return steps.filter { st[it.id]?.state == StepState.OPEN && canLog(it) }
        .sortedWith(
            compareByDescending<Step> { familyLast.getValue(it.family) }
                .thenByDescending { !ev[it.id].isNullOrEmpty() }
                .thenBy { layout.getValue(it.id).second }
                .thenBy { index.getValue(it.id) },
        ).firstOrNull()
}

/**
 * Only for an OPEN step. A session = a local day with evidence for the step (a day spanning midnight counts as 2).
 * Progress = a strictly greater best set, or more qualifying sets than any earlier session. Plateau = >= window+1
 * sessions, none of the last [window] progressing, latest session within 21 days. Mean RPE >= 9 -> Deload; otherwise
 * Regress to the first loggable prerequisite, or Deload at a root.
 */
fun plateauOf(
    step: Step,
    ev: List<Evidence>,
    st: Map<String, StepStatus>,
    nowMs: Long,
    tz: TimeZone,
    window: Int = 4,
    canLog: (Step) -> Boolean = ::canLogPhase1,
): Plateau {
    if (st[step.id]?.state != StepState.OPEN) return Plateau.None
    val sessions = ev.groupBy { dayOf(it.ts, tz) }.toSortedMap().values.toList()
    if (sessions.size < window + 1) return Plateau.None
    if (nowMs - sessions.last().maxOf { it.ts } > STALE_DAYS * DAY_MS) return Plateau.None
    var bestSoFar = Int.MIN_VALUE
    var qualSoFar = -1
    val progressed = sessions.map { day ->
        val best = day.maxOf { it.value }
        val q = day.count(step::qualifies)
        (best > bestSoFar || q > qualSoFar).also { bestSoFar = maxOf(bestSoFar, best); qualSoFar = maxOf(qualSoFar, q) }
    }
    if (progressed.takeLast(window).any { it }) return Plateau.None
    val rpes = sessions.takeLast(window).flatten().mapNotNull { it.rpe }
    if (rpes.isNotEmpty() && rpes.average() >= DELOAD_RPE) return Plateau.Deload
    val back = step.requires.mapNotNull { SKILL_BY_ID[it] }.firstOrNull(canLog)
    return if (back != null) Plateau.Regress(back) else Plateau.Deload
}

/** Row for a hand-logged set of a skill step: exercise_type "other", slug, null form fields; null when reps <= 0. */
fun manualSkillSet(clientId: String, slug: String, reps: Int, loadKg: Double?, rpe: Double?, t: Long): LocalSet? =
    if (reps <= 0) null else LocalSet(clientId, "other", slug, reps, null, null, null, loadKg, rpe, null, t, t, false)
