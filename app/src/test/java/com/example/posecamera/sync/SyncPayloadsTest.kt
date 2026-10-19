package com.example.posecamera.sync

import com.example.posecamera.data.LocalFoodLog
import com.example.posecamera.data.LocalProfile
import com.example.posecamera.data.LocalSet
import com.example.posecamera.data.LocalWeight
import com.example.posecamera.data.Outbox
import com.example.posecamera.pose.ExerciseType
import com.example.posecamera.pose.RepSetSummary
import com.example.posecamera.power.PowerState
import com.google.gson.JsonParser
import java.io.IOException
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun summary(
    type: ExerciseType,
    attempted: Int = 10, clean: Int = 8, rejected: Int = 2, score: Float = 80f,
    total: Int = attempted, hold: Float? = null, slug: String? = null,
) = RepSetSummary(type, attempted, clean, rejected, score, null, emptyList(), total, hold, emptyList(), slug)

private val UTC = TimeZone.getTimeZone("UTC")

class SetMappingTest {
    @Test fun squatMapsToSyncPayloadLikeTheLegacyRequest() {
        val row = summary(ExerciseType.SQUAT).toLocalSet("cid-1", 1_760_000_000_000)!!
        val data = JsonParser.parseString(row.payload()).asJsonObject
        assertEquals(setOf("exercise_type", "rep_count", "clean_count", "attempted_count", "formscore", "timestamp"), data.keySet())
        assertEquals("squat", data["exercise_type"].asString)
        assertEquals(10, data["rep_count"].asInt)
        assertEquals(8, data["clean_count"].asInt)
        assertEquals(2, data["attempted_count"].asInt)
        assertEquals("2025-10-09T08:53:20.000Z", data["timestamp"].asString)
        assertFalse(row.synced)
    }

    @Test fun pushUpUsesPushUpType() {
        val row = summary(ExerciseType.PUSH_UP, 6, 5, 1, 83.3f).toLocalSet("c", 0)!!
        assertEquals("push-up", row.exerciseType)
        assertNull(row.exerciseSlug)
    }

    @Test fun fullyJudgedLiftIsOtherWithSlugAndForm() {
        val row = summary(ExerciseType.LUNGE, attempted = 5, clean = 4, rejected = 1, total = 5, slug = "dumbbell-lunges").toLocalSet("c", 0)!!
        assertEquals("other", row.exerciseType)
        assertEquals("dumbbell-lunges", row.exerciseSlug)
        assertEquals(5, row.repCount)
        assertEquals(4, row.cleanCount)
        assertEquals(1, row.attemptedCount)
    }

    @Test fun partlyJudgedLiftHasNullFormFields() {
        val data = JsonParser.parseString(
            summary(ExerciseType.BICEPS_CURL, attempted = 3, clean = 3, rejected = 0, total = 8, slug = "curl").toLocalSet("c", 0)!!.payload(),
        ).asJsonObject
        assertEquals(8, data["rep_count"].asInt)
        assertFalse(data.has("clean_count") || data.has("attempted_count") || data.has("formscore"))
    }

    @Test fun plankAndZeroRepLiftsAreNotStored() {
        assertNull(summary(ExerciseType.PLANK, 0, 0, 0, 0f, total = 0, hold = 42f, slug = "plank").toLocalSet("c", 0))
        assertNull(summary(ExerciseType.LUNGE, 0, 0, 0, 0f, total = 0, slug = "dumbbell-lunges").toLocalSet("c", 0))
    }
}

class PayloadShapeTest {
    @Test fun foodEntryNeverCarriesQuickAddFieldsOrComputed() {
        val grams = LocalFoodLog("c", "lunch", "2026-10-16", "roti", null, 80.0, null, null, null, null, null, "{\"kcal\":1}", 0, false)
        val o = JsonParser.parseString(grams.payload()).asJsonObject
        assertEquals(setOf("meal", "date", "food_slug", "quantity_g"), o.keySet())
        val serving = grams.copy(servingId = 7)
        assertEquals(setOf("meal", "date", "food_slug", "serving_id"), JsonParser.parseString(serving.payload()).asJsonObject.keySet())
        val quick = LocalFoodLog("c", null, "2026-10-16", null, null, null, "Chai", 90.0, 2.0, null, null, null, 0, false)
        assertEquals(setOf("date", "name", "kcal", "protein_g"), JsonParser.parseString(quick.payload()).asJsonObject.keySet())
    }

    @Test fun weightAndProfile() {
        assertEquals("""{"kg":71.5,"date":"2026-10-16"}""", LocalWeight("c", 71.5, "2026-10-16", 0, false).payload())
        val p = LocalProfile("profile", "male", 1999, 180.0, 75.0, "light", "maintain", null, 0, false)
        assertEquals(setOf("sex", "birth_year", "height_cm", "weight_kg", "activity_level", "goal"), JsonParser.parseString(p.payload()).asJsonObject.keySet())
    }

    @Test fun pushBodyMatchesTheSpecEnvelope() {
        val ops = listOf(
            OutboxOp("op-1", E_FOOD, "cid-1", "upsert", """{"date":"2026-10-16","name":"Chai","kcal":90}""", 1_760_000_000_000),
            OutboxOp("op-2", E_FOOD, "cid-1", "delete", "{}", 1_760_000_001_000),
        )
        val body = pushBody("pixel-7", ops)
        assertEquals("pixel-7", body["device_id"].asString)
        val arr = body.getAsJsonArray("ops")
        assertEquals(listOf("op-1", "op-2"), arr.map { it.asJsonObject["op_id"].asString })
        val first = arr[0].asJsonObject
        assertEquals(setOf("op_id", "entity", "client_id", "action", "data", "client_updated_at"), first.keySet())
        assertEquals("2025-10-09T08:53:20.000Z", first["client_updated_at"].asString)
        assertFalse(arr[1].asJsonObject.has("data")) // deletes carry no data
    }

    @Test fun isoRoundTripAndOffsets() {
        assertEquals(1_760_000_000_000, parseIso(isoUtc(1_760_000_000_000)))
        assertEquals(parseIso("2026-10-16T10:00:00+05:30"), parseIso("2026-10-16T04:30:00Z"))
        assertEquals(parseIso("2026-10-16T04:30:00Z"), parseIso("2026-10-16T04:30:00.123456Z")) // fraction dropped
        assertNull(parseIso("garbage"))
    }
}

class OutboxOrderTest {
    private class MemStore : SyncStore {
        val rows = mutableListOf<Outbox>()
        override fun pendingOps(limit: Int) = rows.sortedBy { it.seq }.take(limit).map { it.toOp() }
        override fun removeOps(opIds: List<String>) { rows.removeAll { it.opId in opIds } }
        override fun recordFailure(opIds: List<String>, error: String) {}
        override fun hasPendingOp(entity: String, clientId: String) = false
        override fun applyUpsert(entity: String, clientId: String, data: String) {}
        override fun applyDelete(entity: String, clientId: String) {}
        override var cursor = 0L
        override var catalogVersions: Map<String, Long> = emptyMap()
    }

    @Test fun opsReachTheServerInWriteOrderAndKeepTheirOpIdsAcrossRetries() {
        val store = MemStore()
        // upsert then delete of the same row must stay in this order, even when the table order is shuffled
        listOf(
            newOp(E_FOOD, "a", "upsert", "{}", 1, "op-1"), newOp(E_WEIGHT, "w", "upsert", "{}", 2, "op-2"),
            newOp(E_FOOD, "a", "delete", "{}", 3, "op-3"),
        ).mapIndexed { i, o -> o.copy(seq = (i + 1).toLong()) }.reversed().forEach { store.rows += it }
        val seen = mutableListOf<String>()
        var fail = true
        val api = object : SyncApi {
            override fun push(ops: List<OutboxOp>): List<PushResult> {
                seen += ops.map { it.opId }
                if (fail) { fail = false; throw IOException("offline") }
                return ops.map { PushResult(it.opId, PushStatus.APPLIED) }
            }
            override fun pull(cursor: Long, limit: Int) = PullPage(emptyList(), cursor, false)
            override fun catalogVersions() = emptyMap<String, Long>()
            override fun downloadCatalog(entity: String) {}
        }
        val engine = SyncEngine(store, api)
        val power = PowerState(80, false, false, 0)
        assertEquals(SyncOutcome.RETRY, engine.run(power))
        assertEquals(SyncOutcome.OK, engine.run(power))
        assertEquals(listOf("op-1", "op-2", "op-3", "op-1", "op-2", "op-3"), seen) // same ids, same order on replay
        assertTrue(store.rows.isEmpty())
    }
}

class ParsingFixtureTest {
    // Copied from backend/SYNC_API.md (push result and pull examples), with a food_logs `computed` block per the spec.
    private val pushResponse = """{"results": [
        {"op_id": "a", "status": "applied"}, {"op_id": "b", "status": "duplicate"},
        {"op_id": "c", "status": "conflict"}, {"op_id": "d", "status": "rejected", "error": "unknown food_slug"}]}"""

    private val pullResponse = """{"changes": [
        {"cursor": 7, "entity": "food_logs", "client_id": "f1", "op": "upsert", "updated_at": "2026-10-16T04:30:00Z",
         "data": {"meal": "lunch", "date": "2026-10-16", "food_slug": "roti", "serving_id": null, "quantity_g": 80.0,
                  "name": null, "kcal": null, "protein_g": null, "carbs_g": null, "fat_g": null,
                  "computed": {"kcal": 238.4, "protein_g": 7.1, "carbs_g": 45.0, "fat_g": 3.2, "fiber_g": null}}},
        {"cursor": 8, "entity": "workout_sets", "client_id": "s1", "op": "upsert", "updated_at": "2026-10-16T04:31:00Z",
         "data": {"exercise_type": "squat", "rep_count": 10, "clean_count": 8, "attempted_count": 2, "formscore": 80.0,
                  "timestamp": "2026-10-16T04:30:00Z", "exercise_slug": null, "load_kg": null, "rpe": null, "set_type": "working"}},
        {"cursor": 9, "entity": "weight_logs", "client_id": "w1", "op": "delete", "updated_at": "2026-10-16T04:32:00Z", "data": null}],
        "next_cursor": 9, "has_more": true}"""

    @Test fun pushResults() {
        val r = parsePushResults(pushResponse)
        assertEquals(listOf(PushStatus.APPLIED, PushStatus.DUPLICATE, PushStatus.CONFLICT, PushStatus.REJECTED), r.map { it.status })
        assertEquals(listOf("a", "b", "c", "d"), r.map { it.opId })
        assertEquals("unknown food_slug", r[3].error)
        assertNull(r[0].error)
    }

    @Test fun unknownFutureStatusIsTerminalNotARetryLoop() {
        assertEquals(PushStatus.REJECTED, parsePushResults("""{"results":[{"op_id":"x","status":"quarantined"}]}""").single().status)
    }

    @Test fun pullPage() {
        val p = parsePull(pullResponse)
        assertEquals(9, p.nextCursor)
        assertTrue(p.hasMore)
        assertEquals(listOf("upsert", "upsert", "delete"), p.changes.map { it.op })
        assertNull(p.changes[2].data)
        assertEquals(7, p.changes[0].cursor)
    }

    @Test fun pulledRowsBecomeSyncedLocalRows() {
        val p = parsePull(pullResponse)
        val food = rowFromPull(p.changes[0].entity, p.changes[0].clientId, p.changes[0].data!!, 5) as LocalFoodLog
        assertEquals("roti", food.foodSlug)
        assertEquals(80.0, food.quantityG!!, 0.0)
        assertNull(food.servingId)
        assertTrue(food.synced)
        assertNotNull(food.computedJson)
        val set = rowFromPull(p.changes[1].entity, p.changes[1].clientId, p.changes[1].data!!, 5) as LocalSet
        assertEquals(parseIso("2026-10-16T04:30:00Z"), set.timestamp)
        assertEquals("squat", set.exerciseType)
        assertNull(set.exerciseSlug)
        assertNull(rowFromPull("something_new", "x", "{}", 0)) // forward compatible
    }

    @Test fun catalogVersions() {
        assertEquals(
            mapOf("foods" to 3L, "exercises" to 1L, "machines" to 1L, "muscles" to 1L, "plans" to 2L),
            parseCatalogVersions("""{"foods": 3, "exercises": 1, "machines": 1, "muscles": 1, "plans": 2}"""),
        )
    }
}

class StreakTest {
    private val day = 86_400_000L
    private val noon = 1_760_000_000_000 / day * day + day / 2

    @Test fun consecutiveDaysEndingTodayOrYesterday() {
        assertEquals(3, streakOf(listOf(noon, noon - day, noon - 2 * day, noon - 2 * day + 1000), noon, UTC))
        assertEquals(2, streakOf(listOf(noon - day, noon - 2 * day), noon, UTC)) // today not yet done
        assertEquals(0, streakOf(listOf(noon - 2 * day), noon, UTC))
        assertEquals(0, streakOf(emptyList(), noon, UTC))
        assertEquals(1, streakOf(listOf(noon, noon - 2 * day), noon, UTC)) // gap breaks it
    }
}

class WorkerMappingTest {
    @Test fun outcomeToWorkResult() {
        assertEquals(Verdict.SUCCESS, verdictOf(SyncOutcome.OK))
        assertEquals(Verdict.SUCCESS, verdictOf(SyncOutcome.PAUSED_AUTH)) // no retry storm with a dead token
        assertEquals(Verdict.RETRY, verdictOf(SyncOutcome.RETRY))
        assertEquals(Verdict.RETRY, verdictOf(SyncOutcome.DEFERRED_POWER))
    }

    @Test fun powerGatingFeedsTheMapping() {
        val hot = PowerState(80, true, false, 3) // thermal SEVERE
        assertFalse(syncAllowed(hot))
        val lowBattery = PowerState(10, false, false, 0) // CRITICAL tier: sync yes, periodic catalog no
        assertTrue(syncAllowed(lowBattery))
        assertFalse(catalogAllowed(lowBattery, userInitiated = false))
        assertTrue(catalogAllowed(lowBattery, userInitiated = true))
        assertTrue(catalogAllowed(PowerState(10, true, true, 0), userInitiated = false)) // charging: not critical
        val engine = SyncEngine(
            object : SyncStore {
                override fun pendingOps(limit: Int) = emptyList<OutboxOp>()
                override fun removeOps(opIds: List<String>) {}
                override fun recordFailure(opIds: List<String>, error: String) {}
                override fun hasPendingOp(entity: String, clientId: String) = false
                override fun applyUpsert(entity: String, clientId: String, data: String) {}
                override fun applyDelete(entity: String, clientId: String) {}
                override var cursor = 0L
                override var catalogVersions: Map<String, Long> = emptyMap()
            },
            object : SyncApi {
                override fun push(ops: List<OutboxOp>) = emptyList<PushResult>()
                override fun pull(cursor: Long, limit: Int) = PullPage(emptyList(), 0, false)
                override fun catalogVersions() = emptyMap<String, Long>()
                override fun downloadCatalog(entity: String) {}
            },
        )
        assertEquals(Verdict.RETRY, verdictOf(engine.run(hot)))
        assertEquals(Verdict.SUCCESS, verdictOf(engine.run(lowBattery)))
    }
}
