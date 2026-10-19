package com.example.posecamera.ui.home

import com.example.posecamera.data.LocalSet
import java.util.TimeZone

private const val DAY_MS = 86_400_000L

private fun dayOf(ms: Long, tz: TimeZone): Long = Math.floorDiv(ms + tz.getOffset(ms), DAY_MS)

/** One flag per day, oldest first, last = today: true when at least one set was logged that local day. */
fun chainOf(timestamps: Collection<Long>, nowMs: Long, tz: TimeZone, days: Int = 14): List<Boolean> {
    val have = timestamps.map { dayOf(it, tz) }.toSet()
    val today = dayOf(nowMs, tz)
    return (days - 1 downTo 0).map { (today - it) in have }
}

data class Pr(val key: String, val loadKg: Double?, val reps: Int, val timestamp: Long)

private fun LocalSet.key() = exerciseSlug ?: exerciseType

/** Epley estimated 1RM when loaded, else plain reps (bodyweight). Only compared within one exercise key. */
fun setScore(loadKg: Double?, reps: Int): Double = loadKg?.takeIf { it > 0 }?.let { it * (1 + reps / 30.0) } ?: reps.toDouble()

private fun LocalSet.score(): Double = setScore(loadKg, repCount)

/** True when (loadKg, reps) beats the best earlier set of [key]; with no earlier set it is a baseline, not a PR. */
fun isPr(history: List<LocalSet>, key: String, loadKg: Double?, reps: Int): Boolean {
    val best = validSets(history).filter { it.key() == key }.maxOfOrNull { it.score() } ?: return false
    return setScore(loadKg, reps) > best
}

/**
 * Sets that beat the previous best of the same exercise (the first ever set is a baseline, not a PR).
 * Newest first, one entry per exercise, at most [limit].
 */
fun prsOf(sets: List<LocalSet>, limit: Int = 3): List<Pr> {
    val best = HashMap<String, Double>()
    val wins = ArrayList<Pr>()
    for (s in validSets(sets).sortedBy { it.timestamp }) {
        val prev = best[s.key()]
        val sc = s.score()
        if (prev == null || sc > prev) {
            best[s.key()] = sc
            if (prev != null) wins += Pr(s.key(), s.loadKg, s.repCount, s.timestamp)
        }
    }
    return wins.asReversed().distinctBy { it.key }.take(limit)
}

/** A set only counts (session, heat, PRs, volume, history) when valid, see [isValid]. */
fun validSets(sets: List<LocalSet>): List<LocalSet> = sets.filter { it.isValid() }

/** Zero/negative reps or a set the server permanently rejected (syncFailed) never counts anywhere. */
fun LocalSet.isValid(): Boolean = repCount > 0 && !syncFailed

/** "1 set" / "2 sets". */
fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

enum class FormBand { NONE, GOOD, OK, LOW }

/** Plumb bob calm: >= 80 good, 60..79 ok, below 60 low, null = no judged data. */
fun formBandOf(avgForm: Float?): FormBand = when {
    avgForm == null -> FormBand.NONE
    avgForm >= 80f -> FormBand.GOOD
    avgForm >= 60f -> FormBand.OK
    else -> FormBand.LOW
}

data class SessionStats(val sets: Int, val reps: Int, val avgForm: Float?, val startMs: Long, val endMs: Long)

/** Latest session = newest set plus every earlier set chained to it with gaps <= [gapMs]. avgForm is 0..100 over judged sets. */
fun lastSession(sets: List<LocalSet>, gapMs: Long = 90 * 60_000L): SessionStats? {
    val sorted = validSets(sets).sortedByDescending { it.timestamp }
    if (sorted.isEmpty()) return null
    val group = ArrayList<LocalSet>().apply { add(sorted[0]) }
    for (s in sorted.drop(1)) {
        if (group.last().timestamp - s.timestamp > gapMs) break
        group += s
    }
    val forms = group.mapNotNull { it.formscore }
    return SessionStats(
        group.size, group.sumOf { it.repCount }, if (forms.isEmpty()) null else forms.average().toFloat(),
        group.last().timestamp, group.first().timestamp,
    )
}

/** "barbell-squat" / "push-up" -> "Barbell squat". */
fun prettyName(key: String): String = key.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
