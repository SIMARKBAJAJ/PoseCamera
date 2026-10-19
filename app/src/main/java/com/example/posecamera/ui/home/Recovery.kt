package com.example.posecamera.ui.home

import android.content.Context
import com.example.posecamera.data.LocalSet
import com.example.posecamera.ui.library.muscleLabel
import com.example.posecamera.ui.library.parseMuscles
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.roundToLong

/*
 * Every number in this file (base hours per muscle size, 1.25 / 0.75 volume scales, 10-set and 3-set
 * thresholds, RPE 9, 3 h session gap, 12..120 h clamp, speed multipliers) is an UNVERIFIED PROJECT DEFAULT.
 * The sources in docs/RECOVERY_EVIDENCE.md give no universal recovery hours. Estimate only, not medical advice.
 */

enum class RecoveryState { RECOVERING, READY, NEVER }

/** [readyAtMs] / [hoursLeft] are null for NEVER; hoursLeft is also null for READY. */
data class MuscleRecovery(val state: RecoveryState, val readyAtMs: Long?, val hoursLeft: Double?)

/** User setting. Multipliers are unverified project defaults. */
enum class RecoverySpeed(val multiplier: Double) { SLOWER(1.25), NORMAL(1.0), FASTER(0.8) }

/** Unverified project default base hours: LARGE 48, MEDIUM 36, SMALL 24. */
enum class MuscleSize(val baseHours: Double) { LARGE(48.0), MEDIUM(36.0), SMALL(24.0) }

val MUSCLE_SIZE: Map<String, MuscleSize> = mapOf(
    "quads" to MuscleSize.LARGE, "glutes" to MuscleSize.LARGE, "hamstrings" to MuscleSize.LARGE,
    "chest" to MuscleSize.LARGE, "lats" to MuscleSize.LARGE, "lower_back" to MuscleSize.LARGE,
    "shoulders" to MuscleSize.MEDIUM, "biceps" to MuscleSize.MEDIUM, "triceps" to MuscleSize.MEDIUM,
    "traps" to MuscleSize.MEDIUM,
    "calves" to MuscleSize.SMALL, "forearms" to MuscleSize.SMALL, "abs" to MuscleSize.SMALL,
)

private const val HOUR_MS = 3_600_000L
private const val SESSION_GAP_MS = 3 * HOUR_MS // unverified project default
private const val MIN_HOURS = 12.0
private const val MAX_HOURS = 120.0

/** The 14 catalog primaryMuscles names onto MuscleMap keys (main 1.0, back's three assist at 0.5). Null = unknown name. */
fun catalogMuscles(name: String): Map<String, Float>? = when (name.trim().lowercase()) {
    "abdominals" -> mapOf("abs" to 1f)
    "abductors" -> mapOf("glutes" to 1f)
    "adductors" -> mapOf("quads" to 1f)
    "back" -> mapOf("lats" to 0.5f, "lower_back" to 0.5f, "traps" to 0.5f)
    "biceps" -> mapOf("biceps" to 1f)
    "calves" -> mapOf("calves" to 1f)
    "chest" -> mapOf("chest" to 1f)
    "forearms" -> mapOf("forearms" to 1f)
    "glutes" -> mapOf("glutes" to 1f)
    "hamstrings" -> mapOf("hamstrings" to 1f)
    "neck" -> mapOf("traps" to 1f)
    "quadriceps" -> mapOf("quads" to 1f)
    "shoulders" -> mapOf("shoulders" to 1f)
    "triceps" -> mapOf("triceps" to 1f)
    else -> null
}

private fun volumeScale(effectiveSets: Float, hardPrimary: Boolean): Double = when {
    effectiveSets >= 10f || hardPrimary -> 1.25
    effectiveSets < 3f -> 0.75
    else -> 1.0
}

/**
 * [resolve] maps a logged slug to muscle weights; the exercise type (squat / push-up) is the fallback when it returns null.
 * Default = the curated table only; the app passes [catalogResolver] so catalog-only exercises start timers too.
 */
fun recoveryFor(
    sets: List<LocalSet>, nowMs: Long, speed: RecoverySpeed, tz: TimeZone,
    resolve: (String?) -> Map<String, Float>? = ::musclesOf,
): Map<String, MuscleRecovery> {
    // per muscle: (timestamp, weight, rpe) of valid sets
    val per = HashMap<String, MutableList<Triple<Long, Float, Double?>>>()
    for (s in validSets(sets)) {
        val m = resolve(s.exerciseSlug) ?: musclesOf(s.exerciseType) ?: continue
        for ((k, w) in m) per.getOrPut(k) { ArrayList() }.add(Triple(s.timestamp, w, s.rpe))
    }
    return MUSCLE_KEYS.associateWith { key ->
        val list = per[key]?.sortedBy { it.first }
        if (list.isNullOrEmpty()) return@associateWith MuscleRecovery(RecoveryState.NEVER, null, null)
        var start = list.size - 1
        while (start > 0 && list[start].first - list[start - 1].first <= SESSION_GAP_MS) start--
        val session = list.subList(start, list.size)
        val eff = session.sumOf { it.second.toDouble() }.toFloat()
        val hard = session.any { it.second >= 1f && (it.third ?: 0.0) >= 9.0 }
        val hours = (MUSCLE_SIZE.getValue(key).baseHours * volumeScale(eff, hard) * speed.multiplier)
            .coerceIn(MIN_HOURS, MAX_HOURS)
        val readyAt = session.last().first + (hours * HOUR_MS).roundToLong()
        if (nowMs < readyAt) MuscleRecovery(RecoveryState.RECOVERING, readyAt, (readyAt - nowMs) / HOUR_MS.toDouble())
        else MuscleRecovery(RecoveryState.READY, readyAt, null)
    }
}

/** Catalog primaryMuscles text ("Quadriceps,Glutes") onto MuscleMap keys, strongest weight per key; null when nothing maps. */
fun catalogFallback(primary: String): Map<String, Float>? {
    val out = HashMap<String, Float>()
    for (part in primary.split(',')) {
        val m = catalogMuscles(part) ?: parseMuscles(part).associateWith { 1f }
        for ((k, w) in m) out[k] = maxOf(out[k] ?: 0f, w)
    }
    return out.ifEmpty { null }
}

/** Curated table first, then the catalog's primary muscles for slugs the table does not know. */
fun catalogResolver(primaryBySlug: Map<String, String>): (String?) -> Map<String, Float>? = { slug ->
    musclesOf(slug)?.takeIf { it.isNotEmpty() } ?: primaryBySlug[slug]?.let(::catalogFallback)
}

/** Share of the window still to go, 0..1 (1 = just trained). Approximation: window taken as 1.25x the muscle's base hours. */
fun recoveryFraction(key: String, r: MuscleRecovery): Float {
    val hours = r.hoursLeft ?: return 0f
    val window = (MUSCLE_SIZE[key]?.baseHours ?: 48.0) * 1.25
    return (hours / window).toFloat().coerceIn(0f, 1f)
}

/** "Chest: 31h 20m left, ready Tue 6:40 pm" per recovering muscle, soonest first. */
fun recoveringLines(map: Map<String, MuscleRecovery>, tz: TimeZone, nowMs: Long): List<String> =
    recoveringList(map).map { (k, r) -> "${muscleLabel(k)}: ${formatLeft(r.hoursLeft ?: 0.0)} left, ready ${formatReady(r.readyAtMs ?: nowMs, tz, nowMs)}" }

fun readyNames(map: Map<String, MuscleRecovery>): List<String> = readyList(map).map { muscleLabel(it.first) }

fun neverNames(map: Map<String, MuscleRecovery>): List<String> =
    map.entries.filter { it.value.state == RecoveryState.NEVER }.map { muscleLabel(it.key) }.sorted()

/** Warn chip text, e.g. "Quads still recovering: 14h": the (at most [max]) most-recovering of [primary]; null when none recovers. */
fun recoveringChip(primary: Collection<String>, map: Map<String, MuscleRecovery>, max: Int = 2): String? {
    val hot = primary.mapNotNull { k -> map[k]?.takeIf { it.state == RecoveryState.RECOVERING }?.let { k to (it.hoursLeft ?: 0.0) } }
        .sortedByDescending { it.second }.take(max)
    if (hot.isEmpty()) return null
    return hot.joinToString(" and ") { muscleLabel(it.first) } + " still recovering: " + ceil(hot.first().second).toInt() + "h"
}

/** "31h 20m", rounded to the nearest minute, never negative. */
fun formatLeft(hoursLeft: Double): String {
    val mins = (hoursLeft * 60).roundToLong().coerceAtLeast(0)
    return "${mins / 60}h ${mins % 60}m"
}

/** "Tue 6:40 pm" in [tz]'s wall clock. [nowMs] is unused for now (reserved so the UI can later say "Today"). */
@Suppress("UNUSED_PARAMETER")
fun formatReady(readyAtMs: Long, tz: TimeZone, nowMs: Long): String =
    SimpleDateFormat("EEE h:mm a", Locale.US).apply { timeZone = tz }.format(readyAtMs)
        .replace("AM", "am").replace("PM", "pm")

/** Muscles already ready (state READY), soonest ready first. */
fun readyList(map: Map<String, MuscleRecovery>): List<Pair<String, MuscleRecovery>> =
    map.entries.filter { it.value.state == RecoveryState.READY }.sortedBy { it.value.readyAtMs }.map { it.key to it.value }

/** Muscles still recovering, soonest ready first. */
fun recoveringList(map: Map<String, MuscleRecovery>): List<Pair<String, MuscleRecovery>> =
    map.entries.filter { it.value.state == RecoveryState.RECOVERING }.sortedBy { it.value.readyAtMs }.map { it.key to it.value }

/** Recovery-speed setting in SharedPreferences (same simple pattern as ConsentStore). Default NORMAL. */
object RecoveryPrefs {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("recovery_prefs", Context.MODE_PRIVATE)

    fun get(context: Context): RecoverySpeed =
        RecoverySpeed.entries.getOrElse(prefs(context).getInt("speed", RecoverySpeed.NORMAL.ordinal)) { RecoverySpeed.NORMAL }

    fun set(context: Context, speed: RecoverySpeed) = prefs(context).edit().putInt("speed", speed.ordinal).apply()
}
