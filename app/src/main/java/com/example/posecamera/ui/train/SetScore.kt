package com.example.posecamera.ui.train

import com.example.posecamera.pose.RepSetSummary

/**
 * SET SCORE 0-100 = existing formScore (clean * 100 / judged reps) rounded to Int.
 * Project default: share of judged reps with no form fault; not a medical or validated measure; thresholds unverified.
 * Null (no score) when nothing was judged or parts could not be seen. Rejected reps stay in the judged count, so they lower the score, never raise it.
 */
fun setScore(s: RepSetSummary): Int? =
    if (s.attemptedReps <= 0 || s.cannotSee.isNotEmpty()) null else Math.round(s.formScore).coerceIn(0, 100)

/** Score for a stored row (LocalSet / SyncedSet). Null when unscored, 0-rep, or the server permanently rejected the set. */
fun rowScore(formscore: Float?, repCount: Int, syncFailed: Boolean = false): Int? =
    if (formscore == null || repCount <= 0 || syncFailed) null else Math.round(formscore).coerceIn(0, 100)

/** Bands are project defaults, not validated: >=85 strong, 60-84 ok, <60 needs work. */
enum class ScoreBand(val label: String) { STRONG("Strong"), OK("OK"), NEEDS_WORK("Needs work") }

fun scoreBand(score: Int): ScoreBand = when {
    score >= 85 -> ScoreBand.STRONG
    score >= 60 -> ScoreBand.OK
    else -> ScoreBand.NEEDS_WORK
}

/** Most common faults first (ties keep first-seen order), max 3, with counts. */
fun topFaults(reasons: List<String>): List<Pair<String, Int>> =
    reasons.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
