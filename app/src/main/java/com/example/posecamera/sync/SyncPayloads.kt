package com.example.posecamera.sync

import com.example.posecamera.data.LocalFoodLog
import com.example.posecamera.data.LocalPlanCompletion
import com.example.posecamera.data.LocalProfile
import com.example.posecamera.data.LocalSet
import com.example.posecamera.data.LocalUserPlan
import com.example.posecamera.data.LocalWeight
import com.example.posecamera.data.Outbox
import com.example.posecamera.data.PLAN_ID
import com.example.posecamera.data.PROFILE_ID
import com.example.posecamera.network.toSubmitLiftSetRequest
import com.example.posecamera.network.toSubmitSetRequest
import com.example.posecamera.pose.RepSetSummary
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

// Pure mapping between local rows and the SYNC_API.md payloads. No Android, no I/O: JVM-testable.
// java.time is avoided on purpose (minSdk 24 has no desugaring configured).

const val E_SETS = "workout_sets"
const val E_FOOD = "food_logs"
const val E_WEIGHT = "weight_logs"
const val E_PROFILE = "profile"
const val E_PLAN = "user_plans"
const val E_COMPLETIONS = "plan_completions"

private val UTC = TimeZone.getTimeZone("UTC")

/** Millisecond precision on purpose: the server's last-writer-wins is "strictly newer", seconds would tie. */
fun isoUtc(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = UTC }.format(Date(ms))

/** Accepts "...Z", "...+05:30" and optional fractional seconds; a bare local-looking stamp is read as UTC. */
fun parseIso(s: String): Long? = runCatching {
    var t = s.replace(Regex("\\.\\d+"), "")
    if (!Regex(".*(Z|[+-]\\d\\d:?\\d\\d)$").matches(t)) t += "Z"
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(t)!!.time
}.getOrNull()

fun dayString(ms: Long, tz: TimeZone): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(ms))

private fun dayNumber(ms: Long, tz: TimeZone): Long = Math.floorDiv(ms + tz.getOffset(ms), 86_400_000L)

/** Same rule as the server's calculate_streak: consecutive days ending today or yesterday. */
fun streakOf(timestamps: Collection<Long>, nowMs: Long, tz: TimeZone): Int {
    val days = timestamps.map { dayNumber(it, tz) }.distinct().sortedDescending()
    val today = dayNumber(nowMs, tz)
    if (days.isEmpty() || days.first() < today - 1) return 0
    var expected = days.first()
    var n = 0
    for (d in days) {
        if (d != expected) break
        n++
        expected--
    }
    return n
}

// ---- legacy set -> sync ----

enum class SaveResult { SAVED, NO_REPS, UNSUPPORTED }

/** Why a set must not be stored (null = storable). rep_count is attemptedReps for squat/push-up, totalReps for lifts; the server needs > 0. */
fun RepSetSummary.saveBlocker(): SaveResult? = when {
    holdSeconds != null -> SaveResult.UNSUPPORTED
    (if (exerciseType.lift) totalReps else attemptedReps) <= 0 -> SaveResult.NO_REPS
    else -> null
}

/**
 * Maps a finished set onto the sync shape using the SAME rules as the legacy /api/sets request builders
 * (exercise_type squat|push-up|other, lift slug, null form fields unless every rep was pose-judged).
 * Null = not stored: plank holds have no field in SYNC_API.md (rep_count must be > 0) and zero-rep lifts are rejected.
 */
fun RepSetSummary.toLocalSet(clientId: String, nowMs: Long): LocalSet? {
    if (saveBlocker() != null) return null
    return if (exerciseType.lift) {
        val r = toSubmitLiftSetRequest()
        LocalSet(clientId, r.exerciseType, r.exerciseSlug, r.repCount, r.cleanCount, r.attemptedCount, r.formscore,
            null, null, null, nowMs, nowMs, false)
    } else {
        val r = toSubmitSetRequest()
        LocalSet(clientId, r.exerciseType, null, r.repCount, r.cleanCount, r.attemptedCount, r.formscore,
            null, null, null, nowMs, nowMs, false)
    }
}

// ---- local row -> push `data` (nulls omitted; the server rejects unknown fields, so only spec fields) ----

private fun JsonObject.put(k: String, v: Any?): JsonObject = apply {
    when (v) {
        null -> {}
        is Number -> addProperty(k, v)
        is Boolean -> addProperty(k, v)
        else -> addProperty(k, v.toString())
    }
}

fun LocalSet.payload(): String = JsonObject()
    .put("exercise_type", exerciseType).put("exercise_slug", exerciseSlug).put("rep_count", repCount)
    .put("clean_count", cleanCount).put("attempted_count", attemptedCount).put("formscore", formscore)
    .put("load_kg", loadKg).put("rpe", rpe).put("set_type", setType).put("timestamp", isoUtc(timestamp))
    .toString()

/** Food entry (slug + serving or grams) or quick add (name + kcal + macros); never both. `computed` is never sent. */
fun LocalFoodLog.payload(): String = JsonObject().put("meal", meal).put("date", date).also {
    if (foodSlug != null) {
        it.put("food_slug", foodSlug)
        if (servingId != null) it.put("serving_id", servingId) else it.put("quantity_g", quantityG)
    } else {
        it.put("name", name).put("kcal", kcal).put("protein_g", proteinG).put("carbs_g", carbsG).put("fat_g", fatG)
    }
}.toString()

fun LocalWeight.payload(): String = JsonObject().put("kg", kg).put("date", date).toString()

fun LocalProfile.payload(): String = JsonObject().put("sex", sex).put("birth_year", birthYear)
    .put("height_cm", heightCm).put("weight_kg", weightKg).put("activity_level", activityLevel)
    .put("goal", goal).put("diet_pref", dietPref).toString()

fun LocalUserPlan.payload(): String =
    JsonObject().put("slug", slug).put("start_date", startDate).put("equipment", equipment).toString()

fun LocalPlanCompletion.payload(): String = JsonObject().put("day_index", dayIndex).put("date", date).toString()

fun newOp(
    entity: String,
    clientId: String,
    action: String,
    payload: String,
    nowMs: Long,
    opId: String = UUID.randomUUID().toString(),
) = Outbox(opId = opId, entity = entity, clientId = clientId, action = action, payload = payload, createdAt = nowMs)

fun Outbox.toOp() = OutboxOp(opId, entity, clientId, action, payload, createdAt)

/** Push request body. Deletes carry no data; ops keep the caller's order (the store returns them by seq). */
fun pushBody(deviceId: String, ops: List<OutboxOp>): JsonObject = JsonObject().apply {
    addProperty("device_id", deviceId)
    add("ops", com.google.gson.JsonArray().also { arr ->
        for (o in ops) arr.add(JsonObject().apply {
            addProperty("op_id", o.opId)
            addProperty("entity", o.entity)
            addProperty("client_id", o.clientId)
            addProperty("action", o.action)
            if (o.action != "delete") add("data", JsonParser.parseString(o.payload))
            addProperty("client_updated_at", isoUtc(o.clientUpdatedAt))
        })
    })
}

// ---- server JSON -> engine types ----

fun parsePushResults(body: String): List<PushResult> =
    JsonParser.parseString(body).asJsonObject.getAsJsonArray("results").map {
        val r = it.asJsonObject
        PushResult(
            r.get("op_id").asString,
            when (r.get("status").asString) {
                "applied" -> PushStatus.APPLIED
                "duplicate" -> PushStatus.DUPLICATE
                "conflict" -> PushStatus.CONFLICT
                else -> PushStatus.REJECTED // unknown future status: terminal, never retried as-is
            },
            r.get("error")?.takeUnless { e -> e.isJsonNull }?.asString,
        )
    }

fun parsePull(body: String): PullPage {
    val o = JsonParser.parseString(body).asJsonObject
    return PullPage(
        o.getAsJsonArray("changes").map {
            val c = it.asJsonObject
            Change(
                c.get("cursor").asLong, c.get("entity").asString, c.get("client_id").asString, c.get("op").asString,
                c.get("data")?.takeUnless { d -> d.isJsonNull }?.toString(),
            )
        },
        o.get("next_cursor").asLong,
        o.get("has_more").asBoolean,
    )
}

fun parseCatalogVersions(body: String): Map<String, Long> =
    JsonParser.parseString(body).asJsonObject.entrySet().associate { it.key to it.value.asLong }

// ---- pulled `data` -> local row (synced = true) ----

private fun JsonObject.s(k: String) = get(k)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.d(k: String) = get(k)?.takeUnless { it.isJsonNull }?.asDouble
private fun JsonObject.i(k: String) = get(k)?.takeUnless { it.isJsonNull }?.asInt
private fun JsonObject.l(k: String) = get(k)?.takeUnless { it.isJsonNull }?.asLong

/** Unknown entity -> null (ignored, forward compatible). */
fun rowFromPull(entity: String, clientId: String, data: String, nowMs: Long): Any? {
    val o = JsonParser.parseString(data).asJsonObject
    return when (entity) {
        E_SETS -> LocalSet(
            clientId, o.s("exercise_type") ?: "other", o.s("exercise_slug"), o.i("rep_count") ?: 0,
            o.i("clean_count"), o.i("attempted_count"), o.d("formscore")?.toFloat(), o.d("load_kg"), o.d("rpe"),
            o.s("set_type"), o.s("timestamp")?.let(::parseIso) ?: nowMs, nowMs, true,
        )
        E_FOOD -> LocalFoodLog(
            clientId, o.s("meal"), o.s("date") ?: "", o.s("food_slug"), o.l("serving_id"), o.d("quantity_g"),
            o.s("name"), o.d("kcal"), o.d("protein_g"), o.d("carbs_g"), o.d("fat_g"),
            o.get("computed")?.takeUnless { it.isJsonNull }?.toString(), nowMs, true,
        )
        E_WEIGHT -> LocalWeight(clientId, o.d("kg") ?: 0.0, o.s("date") ?: "", nowMs, true)
        E_PROFILE -> LocalProfile(
            PROFILE_ID, o.s("sex"), o.i("birth_year"), o.d("height_cm"), o.d("weight_kg"),
            o.s("activity_level"), o.s("goal"), o.s("diet_pref"), nowMs, true,
        )
        E_PLAN -> LocalUserPlan(PLAN_ID, o.s("slug") ?: "", o.s("start_date") ?: "", o.s("equipment"), nowMs, true)
        E_COMPLETIONS -> LocalPlanCompletion(clientId, o.i("day_index") ?: 0, o.s("date") ?: "", nowMs, true)
        else -> null
    }
}
