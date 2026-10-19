package com.example.posecamera.ui.home

import com.example.posecamera.data.LocalSet

private const val DAY_MS = 86_400_000L

/** Keys understood by MuscleMap. */
val MUSCLE_KEYS = setOf(
    "shoulders", "chest", "biceps", "forearms", "abs", "quads", "calves",
    "traps", "triceps", "lats", "lower_back", "glutes", "hamstrings",
)

// main muscles weigh 1, assisting ones 0.5. Keys: catalog slug of each Lifts.all entry, plus the two non-lift types.
private fun t(main: String, aux: String = "") =
    main.split(' ').filter { it.isNotEmpty() }.associateWith { 1f } + aux.split(' ').filter { it.isNotEmpty() }.associateWith { 0.5f }

private val MUSCLES: Map<String, Map<String, Float>> = mapOf(
    "squat" to t("quads glutes", "hamstrings lower_back"),
    "push-up" to t("chest", "triceps shoulders"),
    "dumbbell-lunges" to t("quads glutes", "hamstrings calves"),
    "barbell-deadlift" to t("hamstrings glutes lower_back", "traps lats forearms"),
    "romanian-deadlift" to t("hamstrings glutes", "lower_back"),
    "standing-military-press" to t("shoulders", "triceps traps"),
    "bent-over-barbell-row" to t("lats", "traps biceps lower_back"),
    "dumbbell-bicep-curl" to t("biceps", "forearms"),
    "barbell-glute-bridge" to t("glutes", "hamstrings"),
    "plank" to t("abs", "lower_back shoulders"),
    "goblet-squat" to t("quads glutes", "hamstrings abs"),
    "barbell-squat" to t("quads glutes", "hamstrings lower_back"),
    "barbell-bench-press-medium-grip" to t("chest", "triceps shoulders"),
    "seated-cable-rows" to t("lats", "traps biceps"),
    "pullups" to t("lats", "biceps forearms traps"),
    "wide-grip-lat-pulldown" to t("lats", "biceps"),
    "triceps-pushdown" to t("triceps"),
    "side-lateral-raise" to t("shoulders", "traps"),
    "leg-press" to t("quads", "glutes hamstrings"),
    "lying-leg-curls" to t("hamstrings", "calves"),
    "standing-calf-raises" to t("calves"),
    "crunches" to t("abs"),
    "face-pull" to t("shoulders traps"),
    "dumbbell-step-ups" to t("quads glutes", "calves"),
)

/** Muscle weights for a slug (or "squat"/"push-up" type); null = unknown, contributes nothing. */
fun musclesOf(key: String?): Map<String, Float>? = MUSCLES[key]

/** Sets in the last [days] days (valid reps only), each adds its muscle weights; scaled so the hottest group = 1. Empty when nothing maps. */
fun muscleHeat(sets: List<LocalSet>, nowMs: Long, days: Int = 7): Map<String, Float> {
    val from = nowMs - days * DAY_MS
    val acc = HashMap<String, Float>()
    for (s in validSets(sets)) {
        if (s.timestamp < from) continue
        val m = musclesOf(s.exerciseSlug) ?: musclesOf(s.exerciseType) ?: continue
        for ((k, w) in m) acc[k] = (acc[k] ?: 0f) + w
    }
    val max = acc.values.maxOrNull() ?: return emptyMap()
    return acc.mapValues { it.value / max }
}

private val LABEL = mapOf("lats" to "back", "lower_back" to "lower back")
private val WATCH = listOf("chest", "lats", "shoulders", "quads", "hamstrings", "glutes", "abs", "biceps", "triceps")

/** "Chest garam, back thanda": up to 2 hottest groups, up to 2 untouched major groups. Null when there is no heat. */
fun heatCaption(heat: Map<String, Float>): String? {
    if (heat.isEmpty()) return null
    fun name(k: String) = LABEL[k] ?: k
    val hot = heat.entries.sortedByDescending { it.value }.take(2).filter { it.value >= 0.5f }.map { name(it.key) }
    val cold = WATCH.filter { (heat[it] ?: 0f) <= 0f }.take(2).map(::name)
    val head = hot.joinToString(" + ") + " garam"
    val text = if (cold.isEmpty()) "$head, sab group chale" else "$head, ${cold.joinToString(" + ")} thanda"
    return text.replaceFirstChar { it.uppercase() }
}
