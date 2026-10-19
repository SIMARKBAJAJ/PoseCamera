package com.example.posecamera.ui.progress

import com.example.posecamera.data.LocalSet
import com.example.posecamera.data.LocalWeight
import com.example.posecamera.sync.dayString
import com.example.posecamera.ui.home.setScore
import com.example.posecamera.ui.home.validSets
import java.util.TimeZone

private const val DAY_MS = 86_400_000L

/** Weights from the last [days] days (today included), oldest first. ISO dates compare as strings. */
fun weightWindow(all: List<LocalWeight>, nowMs: Long, tz: TimeZone, days: Int): List<LocalWeight> {
    val from = dayString(nowMs - (days - 1) * DAY_MS, tz)
    return all.filter { it.date >= from }.sortedBy { it.date }
}

/** Best set per exercise. [e1rm] = Epley estimate when loaded, null for bodyweight. */
data class PrBest(val key: String, val loadKg: Double?, val reps: Int, val e1rm: Double?, val timestamp: Long)

/** Same scoring as Home's prsOf (Epley when loaded, else reps), but the single best set of every exercise, newest first. */
fun prBests(sets: List<LocalSet>): List<PrBest> =
    validSets(sets).groupBy { it.exerciseSlug ?: it.exerciseType }.map { (key, ss) ->
        val b = ss.maxWith(compareBy<LocalSet> { setScore(it.loadKg, it.repCount) }.thenBy { it.timestamp })
        val load = b.loadKg?.takeIf { it > 0 }
        PrBest(key, load, b.repCount, load?.let { setScore(it, b.repCount) }, b.timestamp)
    }.sortedByDescending { it.timestamp }

/** Loaded volume (kg x reps) per rolling 7-day bucket, [weeks] buckets, oldest first, last = the 7 days up to now. */
fun weeklyVolume(sets: List<LocalSet>, nowMs: Long, weeks: Int = 6): List<Double> {
    val out = DoubleArray(weeks)
    for (s in validSets(sets)) {
        val load = s.loadKg?.takeIf { it > 0 } ?: continue
        val ago = ((nowMs - s.timestamp) / (7 * DAY_MS)).toInt()
        if (s.timestamp <= nowMs && ago < weeks) out[weeks - 1 - ago] += load * s.repCount
    }
    return out.toList()
}

/** Weight stepper: 0.1 kg steps within 20..400. */
fun stepWeight(kg: Double, dir: Int): Double = (Math.round((kg + dir * 0.1) * 10) / 10.0).coerceIn(20.0, 400.0)

/** Days since 1970-01-01 of an ISO yyyy-MM-dd date (chart x position). */
fun epochDay(iso: String): Long {
    val (y, m, d) = iso.split('-').map { it.toInt() }
    val c = java.util.GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d) }
    return c.timeInMillis / DAY_MS
}
