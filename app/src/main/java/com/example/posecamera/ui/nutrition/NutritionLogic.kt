package com.example.posecamera.ui.nutrition

import com.example.posecamera.data.LocalFoodLog
import com.example.posecamera.data.LocalProfile
import com.google.gson.JsonParser
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.math.roundToInt

// Pure logic for the Nutrition screen (no Compose, unit-tested). Formulas mirror backend/nutrition.py.

data class Macros(val kcal: Double = 0.0, val proteinG: Double = 0.0, val carbsG: Double = 0.0, val fatG: Double = 0.0) {
    operator fun plus(o: Macros) = Macros(kcal + o.kcal, proteinG + o.proteinG, carbsG + o.carbsG, fatG + o.fatG)
}

val MEAL_ORDER = listOf("breakfast", "lunch", "dinner", "snack")

/** Macros of one entry: its own numbers (quick add / locally saved), else the server's frozen `computed` block, else zero. */
fun macrosOf(log: LocalFoodLog): Macros {
    if (log.kcal != null) return Macros(log.kcal, log.proteinG ?: 0.0, log.carbsG ?: 0.0, log.fatG ?: 0.0)
    val c = log.computedJson ?: return Macros()
    return runCatching {
        val o = JsonParser.parseString(c).asJsonObject
        fun d(k: String) = o.get(k)?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0
        Macros(d("kcal"), d("protein_g"), d("carbs_g"), d("fat_g"))
    }.getOrDefault(Macros())
}

fun totalsOf(logs: List<LocalFoodLog>): Macros = logs.fold(Macros()) { a, l -> a + macrosOf(l) }

/** Entries grouped by meal in [MEAL_ORDER]; every meal is present (maybe empty). */
fun byMeal(logs: List<LocalFoodLog>): Map<String, List<LocalFoodLog>> = MEAL_ORDER.associateWith { m -> logs.filter { it.meal == m } }

/** Macros of [grams] of a food given per-100 g values. */
fun scaled(per100: Macros, grams: Double): Macros {
    val f = grams / 100.0
    return Macros(per100.kcal * f, per100.proteinG * f, per100.carbsG * f, per100.fatG * f)
}

const val DEFAULT_KCAL = 2000.0
const val DEFAULT_PROTEIN_G = 120.0

/** [isDefault] = the profile was incomplete, so these are the fixed 2000 kcal / 120 g protein placeholders. */
data class Targets(val kcal: Double, val proteinG: Double, val carbsG: Double, val fatG: Double, val isDefault: Boolean)

private val ACTIVITY = mapOf("sedentary" to 1.2, "light" to 1.375, "moderate" to 1.55, "active" to 1.725, "very_active" to 1.9)
private val GOAL_ADJ = mapOf("lose" to -0.15, "maintain" to 0.0, "gain" to 0.075)
private val PROTEIN_PER_KG = mapOf("lose" to 2.0, "maintain" to 1.6, "gain" to 1.8)

/** Mifflin-St Jeor resting kcal/day. */
fun bmrMifflin(sex: String, weightKg: Double, heightCm: Double, age: Int): Double =
    10 * weightKg + 6.25 * heightCm - 5 * age + (if (sex == "male") 5 else -161)

fun tdee(bmr: Double, activity: String?): Double = bmr * (ACTIVITY[activity] ?: ACTIVITY.getValue("light"))

private val DEFAULT_TARGETS = Targets(
    DEFAULT_KCAL, DEFAULT_PROTEIN_G, (DEFAULT_KCAL - DEFAULT_PROTEIN_G * 4 - 65 * 9) / 4, 65.0, true,
)

/** Same split as the backend: protein by goal per kg, fat at its floor, carbs fill the rest. Defaults when sex/year/height/weight are missing. */
fun targetsOf(p: LocalProfile?, thisYear: Int): Targets {
    val sex = p?.sex?.takeIf { it == "male" || it == "female" }
    val by = p?.birthYear; val h = p?.heightCm; val w = p?.weightKg
    if (p == null || sex == null || by == null || h == null || w == null || w <= 0 || h <= 0 || by !in 1900..thisYear) return DEFAULT_TARGETS
    val goal = p.goal?.takeIf { it in GOAL_ADJ } ?: "maintain"
    var kcal = (tdee(bmrMifflin(sex, w, h, thisYear - by), p.activityLevel) * (1 + GOAL_ADJ.getValue(goal))).roundToInt().coerceAtLeast(0)
    if (goal == "lose") kcal = maxOf(kcal, if (sex == "female") 1200 else 1500)
    val protein = PROTEIN_PER_KG.getValue(goal) * w
    val fat = maxOf(0.8 * w, 0.2 * kcal / 9)
    val carbs = maxOf(0.0, (kcal - protein * 4 - fat * 9) / 4)
    return Targets(kcal.toDouble(), protein, carbs, fat, false)
}

/** ISO yyyy-MM-dd shifted by [delta] days (calendar arithmetic, no time zone involved). */
fun shiftDay(iso: String, delta: Int): String {
    val (y, m, d) = iso.split('-').map { it.toInt() }
    val c = GregorianCalendar(y, m - 1, d).apply { add(java.util.Calendar.DAY_OF_MONTH, delta) }
    return String.format(Locale.US, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
}

private val STEP_G = 10.0

/** Quantity stepper: 10 g steps, 10..5000 g (the server accepts 1..5000). */
fun stepGrams(g: Double, dir: Int): Double = (g + dir * STEP_G).coerceIn(10.0, 5000.0)

/** Curated staple (data/common_foods.json): [label] is shown, [slug] is looked up in the local catalog. */
data class CommonFood(val label: String, val slug: String)

val COMMON_FOODS = listOf(
    CommonFood("Poha (flattened rice, raw)", "ifct-a011-rice-flakes"),
    CommonFood("Roti / chapati", "usda-sr-174075-bread-chapati-or-roti-whole-wheat-commercially-prepared-frozen"),
    CommonFood("Atta (wheat flour, raw)", "ifct-a019-wheat-flour-atta"),
    CommonFood("Paratha", "usda-sr-174076-bread-paratha-whole-wheat-commercially-prepared-frozen"),
    CommonFood("Cooked rice", "usda-sr-168878-rice-white-long-grain-regular-enriched-cooked"),
    CommonFood("Rice (raw)", "ifct-a015-rice-raw-milled"),
    CommonFood("Toor dal (raw)", "ifct-b021-red-gram-dal"),
    CommonFood("Moong dal (raw)", "ifct-b010-green-gram-dal"),
    CommonFood("Cooked dal (lentils)", "usda-sr-172421-lentils-mature-seeds-cooked-boiled-without-salt"),
    CommonFood("Rajma (raw)", "ifct-b019-rajmah-brown"),
    CommonFood("Chana (whole, raw)", "ifct-b002-bengal-gram-whole"),
    CommonFood("Boiled egg", "usda-sr-173424-egg-whole-cooked-hard-boiled"),
    CommonFood("Curd / dahi", "usda-ff-2259793-yogurt-plain-whole-milk"),
    CommonFood("Chaas / buttermilk", "usda-sr-170874-milk-buttermilk-fluid-cultured-lowfat"),
    CommonFood("Milk (cow)", "ifct-l002-milk-whole-cow"),
    CommonFood("Milk (buffalo)", "ifct-l001-milk-whole-buffalo"),
    CommonFood("Chicken breast (raw)", "ifct-n003-chicken-poultry-breast-skinless"),
    CommonFood("Chicken breast (roasted)", "usda-sr-171477-chicken-broilers-or-fryers-breast-meat-only-cooked-roasted"),
    CommonFood("Fish (rohu, raw)", "ifct-s006-rohu"),
    CommonFood("Oats (dry)", "usda-sr-173904-cereals-oats-regular-and-quick-not-fortified-dry"),
    CommonFood("Banana", "ifct-e012-banana-ripe-robusta"),
    CommonFood("Apple", "ifct-e001-apple-big"),
    CommonFood("Almonds", "ifct-h001-almond"),
    CommonFood("Peanuts", "ifct-h012-ground-nut"),
    CommonFood("Ghee", "usda-sr-171314-butter-clarified-butter-ghee"),
)

/** Curated list order, each paired with its catalog row; slugs missing from [found] are skipped. */
fun resolveCommon(found: List<com.example.posecamera.data.CatalogFood>, curated: List<CommonFood> = COMMON_FOODS): List<Pair<CommonFood, com.example.posecamera.data.CatalogFood>> {
    val bySlug = found.associateBy { it.slug }
    return curated.mapNotNull { c -> bySlug[c.slug]?.let { c to it } }
}

/** Fewer resolved staples than this: plain alphabetical list, no COMMON header. */
const val MIN_COMMON = 4

private val USDA_PREFIX = Regex("""^usda\s+(sr|ff)\s+\d+\s+""",RegexOption.IGNORE_CASE)

/** Short display name for a food: strips a "usda sr 174075" prefix, cuts at the first comma or parenthesis, capitalises. */
fun shortFoodName(name: String): String {
    val t = name.trim().replace(USDA_PREFIX, "")
    val cut = t.indexOfFirst { it == ',' || it == '(' }
    val s = (if (cut > 0) t.substring(0, cut) else t).trim()
    return s.replaceFirstChar { it.uppercase() }
}

private val SLUG_CODE = Regex("""^(ifct\s+[a-z]?\d+|usda\s+(sr|ff)\s+\d+)\s+""", RegexOption.IGNORE_CASE)

/** Display name for a log row: stored name, else curated label for a known slug, else the slug with its catalog code removed, else "Food". */
fun foodLabel(name: String?, slug: String?): String {
    if (!name.isNullOrBlank()) return shortFoodName(name)
    if (slug.isNullOrBlank()) return "Food"
    COMMON_FOODS.firstOrNull { it.slug == slug }?.let { return shortFoodName(it.label) }
    val s = com.example.posecamera.ui.home.prettyName(slug).replace(SLUG_CODE, "").trim()
    return s.replaceFirstChar { it.uppercase() }.ifEmpty { "Food" }
}
