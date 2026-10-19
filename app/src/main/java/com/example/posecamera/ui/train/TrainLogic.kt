package com.example.posecamera.ui.train

import com.example.posecamera.data.LocalSet
import com.example.posecamera.ui.home.validSets
import com.example.posecamera.lifts.LiftConfig
import com.example.posecamera.pose.ExerciseAnalysis
import com.example.posecamera.pose.FormState
import com.example.posecamera.pose.RepRules
import com.example.posecamera.pose.RepSetSummary
import com.example.posecamera.ui.design.g3d.plateBreakdown
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

// Pure logic for the Train tab (no Compose, unit-tested).

const val KG_STEP = 2.5
const val MAX_KG = 500.0
const val MAX_REPS = 100
val REST_CHOICES = listOf(60, 90, 120)

fun stepKg(kg: Double, dir: Int): Double = (kg + dir * KG_STEP).coerceIn(0.0, MAX_KG)

fun stepReps(reps: Int, dir: Int): Int = (reps + dir).coerceIn(1, MAX_REPS)

/** RPE is optional: null -> up gives 8, 6 -> down gives null, otherwise 0.5 steps within 6..10. */
fun stepRpe(rpe: Double?, dir: Int): Double? {
    if (rpe == null) return if (dir > 0) 8.0 else null
    val v = rpe + dir * 0.5
    return if (v < 6.0) null else v.coerceAtMost(10.0)
}

/** Fraction of the rest left, 1 -> 0. */
fun restFraction(totalSec: Int, elapsedMs: Long): Float =
    if (totalSec <= 0) 0f else (1f - elapsedMs / (totalSec * 1000f)).coerceIn(0f, 1f)

fun restSecondsLeft(totalSec: Int, elapsedMs: Long): Int =
    ceil((totalSec * 1000L - elapsedMs) / 1000.0).toInt().coerceIn(0, totalSec.coerceAtLeast(0))

/** "20 | 2x25 | 2x15": bar, then each plate size loaded on both sides. */
fun plateText(kg: Double, bar: Float = 20f): String {
    fun n(v: Float) = if (v % 1f == 0f) v.toInt().toString() else String.format(Locale.US, "%s", v)
    val per = plateBreakdown(kg.toFloat(), bar)
    if (per.isEmpty()) return "${n(bar)} bar"
    return (listOf(n(bar)) + per.groupingBy { it }.eachCount().map { (p, c) -> "${2 * c}x${n(p)}" }).joinToString(" | ")
}

private const val DAY_MS = 86_400_000L
private fun dayOf(ms: Long, tz: TimeZone) = Math.floorDiv(ms + tz.getOffset(ms), DAY_MS)

fun setsToday(history: List<LocalSet>, key: String, nowMs: Long, tz: TimeZone): List<LocalSet> =
    validSets(history).filter { (it.exerciseSlug ?: it.exerciseType) == key && dayOf(it.timestamp, tz) == dayOf(nowMs, tz) }
        .sortedBy { it.timestamp }

/** The most recent earlier day's sets for [key], oldest first (ghost values). */
fun previousSets(history: List<LocalSet>, key: String, nowMs: Long, tz: TimeZone): List<LocalSet> {
    val today = dayOf(nowMs, tz)
    val older = validSets(history).filter { (it.exerciseSlug ?: it.exerciseType) == key && dayOf(it.timestamp, tz) < today }
    val last = older.maxOfOrNull { dayOf(it.timestamp, tz) } ?: return emptyList()
    return older.filter { dayOf(it.timestamp, tz) == last }.sortedBy { it.timestamp }
}

/** Lifts that can be logged by hand (plank is a timed hold with no rep field), most recently trained first. */
fun orderedLifts(all: List<LiftConfig>, sets: List<LocalSet>): List<LiftConfig> {
    val rank = HashMap<String, Int>()
    validSets(sets).sortedByDescending { it.timestamp }.forEach { s -> s.exerciseSlug?.let { rank.putIfAbsent(it, rank.size) } }
    return all.filter { it.repJoint != null }.sortedBy { rank[it.catalogSlug] ?: Int.MAX_VALUE }
}

/** A hand-logged lift set: reps counted, nothing pose-judged, so the existing mapper leaves the form fields null. */
fun manualSummary(config: LiftConfig, reps: Int) = RepSetSummary(
    exerciseType = config.type, attemptedReps = 0, cleanReps = 0, rejectedReps = 0, formScore = 0f,
    mostCommonRejectionReason = null, rejectionReasons = emptyList(), totalReps = reps, catalogSlug = config.catalogSlug,
)

// ---- live HUD ----

enum class FormBand { SAHI, HALDI, SINDOOR }

fun formBand(score: Float): FormBand = when {
    score >= 85f -> FormBand.SAHI
    score >= 60f -> FormBand.HALDI
    else -> FormBand.SINDOOR
}

fun bandOf(state: FormState): FormBand? = when (state) {
    FormState.GREEN -> FormBand.SAHI
    FormState.YELLOW -> FormBand.HALDI
    FormState.RED -> FormBand.SINDOOR
    FormState.GREY -> null
}

/**
 * 0 = rest, 1 = bottom of the rep, from the live (rest-high normalised) movement angle. No per-rep ROM is exposed
 * by the pose layer, so this tracks the current rep phase, not the finished rep's depth.
 */
fun depthOf(angle: Float?, rules: RepRules): Float {
    if (angle == null) return 0f
    val span = rules.lockoutDegrees - rules.bottomEntryDegrees
    return if (span <= 0f) 0f else ((rules.lockoutDegrees - angle) / span).coerceIn(0f, 1f)
}

/** -1..1 for the plumb line: the analyzer's body deviation when it has one (30 deg = full swing), else the form state. */
fun deviationOf(a: ExerciseAnalysis?): Float {
    if (a == null) return 0f
    a.bodyDeviationDegrees?.let { return (it / 30f).coerceIn(-1f, 1f) }
    return when (a.state) {
        FormState.GREEN, FormState.GREY -> 0f
        FormState.YELLOW -> 0.5f
        FormState.RED -> 1f
    }
}

/** The plates card is for bar lifts only (these six); bodyweight, dumbbell, cable and machine lifts never show it. */
val PLATE_LIFTS = setOf(
    "barbell-squat", "barbell-bench-press-medium-grip", "barbell-deadlift", "romanian-deadlift",
    "standing-military-press", "bent-over-barbell-row",
)

fun showsPlates(slug: String): Boolean = slug in PLATE_LIFTS
