package com.example.posecamera.ui.library

import com.example.posecamera.demo.DEMO_CUES
import com.example.posecamera.demo.DemoLift
import com.example.posecamera.lifts.LiftConfig
import com.example.posecamera.lifts.Lifts
import com.example.posecamera.sync.sanitizeQuery
import com.example.posecamera.ui.design.g3d.MuscleSide
import com.example.posecamera.ui.home.musclesOf
import kotlin.math.PI

/** Query as the DB wants it: lower-case, no LIKE wildcards, single spaces. */
fun searchKey(q: String): String = sanitizeQuery(q).split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")

/** Muscle filter chips, in display order (keys understood by MuscleMap). */
val FILTER_MUSCLES = listOf(
    "chest", "lats", "shoulders", "traps", "biceps", "triceps", "forearms", "abs",
    "lower_back", "glutes", "quads", "hamstrings", "calves",
)

// substring of the (lower-cased) catalog muscle name that means this MuscleMap key, e.g. "Abdominals" -> abs
private val MUSCLE_LIKE = mapOf(
    "shoulders" to "shoulder", "chest" to "chest", "biceps" to "bicep", "forearms" to "forearm", "abs" to "abdom",
    "quads" to "quad", "calves" to "calv", "traps" to "trap", "triceps" to "tricep", "lats" to "back",
    "lower_back" to "lower back", "glutes" to "glute", "hamstrings" to "hamstring",
)

/**
 * SQL LIKE fragment for a chip key; '' (no filter) for null/unknown. Never contains % _ \ .
 * The catalog has ONE 'back' muscle (no lats/traps/lower back), so Traps and Lower back filter back-primary
 * exercises further by [nameLikes]: a name-based project heuristic, not catalog data.
 */
fun muscleLike(key: String?): String = if (key == "traps" || key == "lower_back") "back" else MUSCLE_LIKE[key] ?: ""

/** Name fragments (lower-case, max 3, no % _ \) that narrow back-primary exercises for the Traps / Lower back chips. Heuristic. Neck-primary exercises are not included under Traps. */
fun nameLikes(key: String?): List<String> = when (key) {
    "traps" -> listOf("shrug", "upright", "face pull")
    "lower_back" -> listOf("deadlift", "extension", "good morning")
    else -> emptyList()
}

fun muscleLabel(key: String): String = when (key) {
    "lats" -> "Back"
    "lower_back" -> "Lower back"
    else -> key.replaceFirstChar { it.uppercase() }
}

/** MuscleMap keys named by the catalog's comma separated primaryMuscles text. */
fun parseMuscles(primary: String): Set<String> {
    val t = primary.lowercase().trim()
    val notLower = t.replace("lower back", "") // "lower back" must not also count as the catalog word 'back' (lats)
    return MUSCLE_LIKE.filter { (k, like) -> like in (if (k == "lats") notLower else t) }.keys + EXTRA_LIKE.filter { (like, _) -> like in t }.values
}

// catalog names with no chip of their own (or other spellings), mapped to the nearest drawn group so non-demo rows still highlight.
// Matching is on the lower-cased text by substring, so plural/singular ("glute"/"glutes") and long forms ("quadriceps") all hit.
private val EXTRA_LIKE = mapOf(
    "middle back" to "lats", "mid back" to "lats", "lat" to "lats","neck" to "traps", "abductor" to "glutes", "adductor" to "quads",
    "delt" to "shoulders", "abs" to "abs", "oblique" to "abs", "core" to "abs", "pec" to "chest", "calf" to "calves",
    "trapez" to "traps", "lowerback" to "lower_back", "lower_back" to "lower_back", "erector" to "lower_back",
)

/** Heat for the muscle maps: the curated table (1 = main, 0.5 = assisting) when the lift is known, else catalog primaries at 1. */
fun heatOf(slug: String, primary: String): Map<String, Float> =
    musclesOf(slug)?.takeIf { it.isNotEmpty() } ?: parseMuscles(primary).associateWith { 1f }

/** Primary = full heat, secondary = the rest, each in MuscleMap key order. */
fun primaryOf(heat: Map<String, Float>) = heat.filterValues { it >= 1f }.keys.toList()
fun secondaryOf(heat: Map<String, Float>) = heat.filterValues { it in 0.01f..0.99f }.keys.toList()

private val BACK_ONLY = setOf("traps", "triceps", "lats", "lower_back", "glutes", "hamstrings")
private val FRONT_ONLY = setOf("chest", "biceps", "abs", "quads")

/** Which side of the body shows more of the worked muscles (front on ties). */
fun sideFor(heat: Map<String, Float>): MuscleSide {
    val back = heat.filterKeys { it in BACK_ONLY }.values.sum()
    val front = heat.filterKeys { it in FRONT_ONLY }.values.sum()
    return if (back > front) MuscleSide.BACK else MuscleSide.FRONT
}

// ---- lift <-> demo mapping ----

fun liftOf(slug: String): LiftConfig? = Lifts.all.firstOrNull { it.catalogSlug == slug }

/** The DemoLift for a catalog slug: the 22 Lifts by catalogSlug (names match except the cable row), plus the two non-lift demos. */
fun demoOf(slug: String): DemoLift? = when (slug) {
    "squat", "bodyweight-squat", "bodyweight-squats", "air-squat", "air-squats" -> DemoLift.SQUAT
    "push-up", "push-ups", "pushup", "pushups" -> DemoLift.PUSH_UP
    else -> liftOf(slug)?.let { c ->
        val n = if (c.type.name == "SEATED_CABLE_ROW") "SEATED_ROW" else c.type.name
        DemoLift.entries.firstOrNull { it.name == n }
    }
}

/** Phase that lands exactly on the good-form key (thumbnails, ghost frame). */
fun targetPhase(lift: DemoLift): Float = lift.target.toFloat() / lift.keys.size

/** Bar-based lifts: DEMO_PROPS draws a hand/shoulder bar for these (plus the row and glute bridge, which have no prop). */
fun isBarbell(slug: String, machine: String?): Boolean =
    "barbell" in slug || slug == "standing-military-press" || machine?.contains("barbell", ignoreCase = true) == true

/** Hand-loggable through the Train flow (the plank is a timed hold with no rep field). */
fun loggableType(slug: String): String? = liftOf(slug)?.takeIf { it.repJoint != null }?.type?.name
    ?: when (demoOf(slug)) { DemoLift.SQUAT -> "SQUAT"; DemoLift.PUSH_UP -> "PUSH_UP"; else -> null } // camera-only: no workout-log lift

/** Exercise types that have no WorkoutLog lift: Train opens the Form coach with them pre-selected. */
val CAMERA_ONLY = setOf("SQUAT", "PUSH_UP")

class Cues(val how: List<String>, val watch: List<String>)

/** how = the demo's general cues (else the lift's start hint); watch = the lift's fault cues. Read-only view of Lifts. */
fun cuesFor(slug: String): Cues {
    val lift = liftOf(slug)
    val how = demoOf(slug)?.let { DEMO_CUES[it] } ?: lift?.let { listOf(it.guidance.substringAfterLast('\n')) } ?: emptyList()
    return Cues(how, lift?.faults?.map { it.cue }.orEmpty())
}

// ---- camera / paging ----

/** Target yaw expressed as the nearest equivalent of [current], so a preset never spins the long way round. */
fun nearestYaw(current: Float, target: Float): Float {
    val tau = (2 * PI).toFloat()
    var d = (target - current) % tau
    if (d > PI) d -= tau else if (d < -PI) d += tau
    return current + d
}

/** Grow the page when the last visible row is within [ahead] of the end and the previous page came back full. */
fun shouldLoadMore(lastVisible: Int, size: Int, limit: Int, ahead: Int = 5): Boolean =
    size > 0 && size >= limit && lastVisible >= size - ahead
