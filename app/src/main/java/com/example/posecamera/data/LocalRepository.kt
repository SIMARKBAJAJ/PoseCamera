package com.example.posecamera.data

import com.example.posecamera.network.HistoryResponse
import com.example.posecamera.network.SyncedSet
import com.example.posecamera.pose.RepSetSummary
import com.example.posecamera.skills.SKILL_SLUGS
import com.example.posecamera.skills.manualSkillSet
import com.example.posecamera.sync.E_COMPLETIONS
import com.example.posecamera.sync.E_FOOD
import com.example.posecamera.sync.E_PLAN
import com.example.posecamera.sync.E_PROFILE
import com.example.posecamera.sync.E_SETS
import com.example.posecamera.sync.E_WEIGHT
import com.example.posecamera.sync.SaveResult
import com.example.posecamera.sync.saveBlocker
import com.example.posecamera.sync.isoUtc
import com.example.posecamera.sync.newOp
import com.example.posecamera.sync.payload
import com.example.posecamera.sync.prefixRange
import com.example.posecamera.sync.sanitizeQuery
import com.example.posecamera.sync.streakOf
import com.example.posecamera.sync.toLocalSet
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

const val PAGE = 30
val MEALS = setOf("breakfast", "lunch", "snack", "dinner")

/**
 * Offline-first data layer: every write is ONE Room transaction (row + outbox op) and then pokes [onSaved]
 * (which enqueues SyncWorker). Reads are Flows over Room, pending rows included. Everything is suspend/Flow so it
 * never runs on the camera thread; callers must not use it from the pose callback.
 */
class LocalRepository(
    db: AppDatabase,
    private val onSaved: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val user = db.user()
    private val catalog = db.catalog()

    private fun id() = UUID.randomUUID().toString()

    // ---- sets ----

    /** Not SAVED when the set is not storable (plank hold: no server field; rep_count <= 0 for any exercise). Nothing is enqueued then. */
    suspend fun saveSet(summary: RepSetSummary, loadKg: Double? = null, rpe: Double? = null): SaveResult {
        summary.saveBlocker()?.let { return it }
        val t = now()
        val clientId = id()
        val row = summary.toLocalSet(clientId, t)?.copy(loadKg = loadKg, rpe = rpe) ?: return SaveResult.UNSUPPORTED
        user.save(row, newOp(E_SETS, clientId, "upsert", row.payload(), t))
        onSaved()
        return SaveResult.SAVED
    }

    /** Newest sets first (pending rows included), for Home derivations. */
    fun recentSets(limit: Int = 500): Flow<List<LocalSet>> = user.sets(limit, 0)

    /** All skill-step sets, unlimited (a 500-row window would "un-achieve" old steps). */
    fun skillSets(): Flow<List<LocalSet>> = user.skillSets(SKILL_SLUGS)

    /** Hand-logged set of a catalog step: exercise_type "other" + slug, synced like any lift set. NO_REPS when reps <= 0. */
    suspend fun saveManualSet(slug: String, reps: Int, loadKg: Double? = null, rpe: Double? = null): SaveResult {
        val t = now()
        val clientId = id()
        val row = manualSkillSet(clientId, slug, reps, loadKg, rpe, t) ?: return SaveResult.NO_REPS
        user.save(row, newOp(E_SETS, clientId, "upsert", row.payload(), t))
        onSaved()
        return SaveResult.SAVED
    }

    fun catalogPlan(slug: String): Flow<CatalogPlan?> = catalog.plan(slug)

    fun catalogPlans(): Flow<List<CatalogPlan>> = catalog.plans()

    /** History and streak from Room (pending rows included) in the legacy /api/history shape. */
    fun history(limit: Int = 200, tz: () -> TimeZone = { TimeZone.getDefault() }): Flow<HistoryResponse> =
        combine(user.sets(limit, 0), user.setTimestamps()) { sets, stamps ->
            HistoryResponse(
                streakOf(stamps, now(), tz()),
                sets.map {
                    SyncedSet(it.exerciseType, it.exerciseSlug, it.repCount, it.cleanCount, it.attemptedCount,
                        it.formscore, isoUtc(it.timestamp))
                },
            )
        }

    // ---- food ----

    fun foodLogs(date: String): Flow<List<LocalFoodLog>> = user.foodLogs(date)

    suspend fun logFood(date: String, meal: String, foodSlug: String, quantityG: Double? = null, servingId: Long? = null): String {
        require((quantityG != null) != (servingId != null)) { "give quantityG or servingId, not both" }
        return saveFood(LocalFoodLog(id(), meal, date, foodSlug, servingId, quantityG, null, null, null, null, null, null, 0, false))
    }

    suspend fun quickAddFood(date: String, meal: String, name: String, kcal: Double, proteinG: Double? = null, carbsG: Double? = null, fatG: Double? = null): String =
        saveFood(LocalFoodLog(id(), meal, date, null, null, null, name, kcal, proteinG, carbsG, fatG, null, 0, false))

    /** Pass a modified copy of an existing row (same clientId). */
    suspend fun editFoodLog(row: LocalFoodLog): String = saveFood(row)

    private suspend fun saveFood(row: LocalFoodLog): String {
        require(row.meal in MEALS) { "meal must be one of $MEALS (the server requires it)" }
        val t = now()
        val r = row.copy(updatedAt = t, synced = false)
        user.save(r, newOp(E_FOOD, r.clientId, "upsert", r.payload(), t))
        onSaved()
        return r.clientId
    }

    suspend fun deleteFoodLog(clientId: String) {
        user.deleteFoodLog(clientId, newOp(E_FOOD, clientId, "delete", "{}", now()))
        onSaved()
    }

    // ---- weight (one row per day: a second entry for a date reuses that row's client_id) ----

    fun weights(page: Int = 0): Flow<List<LocalWeight>> = user.weights(PAGE, page * PAGE)

    /** Newest [limit] weights (not paged), for charts. */
    fun recentWeights(limit: Int = 120): Flow<List<LocalWeight>> = user.weights(limit, 0)

    suspend fun logWeight(kg: Double, date: String) {
        val t = now()
        val row = LocalWeight(user.weightOn(date)?.clientId ?: id(), kg, date, t, false)
        user.save(row, newOp(E_WEIGHT, row.clientId, "upsert", row.payload(), t))
        onSaved()
    }

    suspend fun deleteWeight(clientId: String) {
        user.deleteWeight(clientId, newOp(E_WEIGHT, clientId, "delete", "{}", now()))
        onSaved()
    }

    // ---- profile / plan ----

    fun profile(): Flow<LocalProfile?> = user.profile()

    suspend fun saveProfile(p: LocalProfile) {
        val t = now()
        val row = p.copy(clientId = PROFILE_ID, updatedAt = t, synced = false)
        user.save(row, newOp(E_PROFILE, PROFILE_ID, "upsert", row.payload(), t))
        onSaved()
    }

    fun userPlan(): Flow<LocalUserPlan?> = user.userPlan()

    suspend fun assignPlan(slug: String, startDate: String, equipment: String?) {
        val t = now()
        val row = LocalUserPlan(PLAN_ID, slug, startDate, equipment, t, false)
        user.save(row, newOp(E_PLAN, PLAN_ID, "upsert", row.payload(), t))
        onSaved()
    }

    fun planCompletions(page: Int = 0): Flow<List<LocalPlanCompletion>> = user.planCompletions(PAGE, page * PAGE)

    suspend fun completeDay(dayIndex: Int, date: String) {
        val t = now()
        val row = LocalPlanCompletion(user.completionOn(date)?.clientId ?: id(), dayIndex, date, t, false)
        user.save(row, newOp(E_COMPLETIONS, row.clientId, "upsert", row.payload(), t))
        onSaved()
    }

    // ---- catalog reads (LIMIT/OFFSET pages; blank query = full alphabetical list) ----

    fun exercises(query: String = "", page: Int = 0): Flow<List<CatalogExercise>> {
        val q = sanitizeQuery(query)
        val (lo, hi) = prefixRange(q)
        return if (q.isEmpty()) catalog.exercises(PAGE, page * PAGE) else catalog.searchExercises(q, lo, hi, PAGE, page * PAGE)
    }

    /** Library list: name search AND muscle substring ([muscleLike], '' = any), first [limit] rows (grow it to page). */
    fun browseExercises(query: String, muscleLike: String, nameLikes: List<String>, limit: Int): Flow<List<CatalogExercise>> {
        val q = sanitizeQuery(query)
        val (lo, hi) = prefixRange(q)
        val n = nameLikes + List(3) { "" } // '' = unused
        return catalog.browseExercises(q, lo, hi, muscleLike, n[0], n[1], n[2], limit)
    }

    fun exercisesBySlugs(slugs: List<String>): Flow<List<CatalogExercise>> = catalog.exercisesBySlugs(slugs)

    fun foods(query: String = "", page: Int = 0): Flow<List<CatalogFood>> {
        val q = sanitizeQuery(query)
        val (lo, hi) = prefixRange(q)
        return if (q.isEmpty()) catalog.foods(PAGE, page * PAGE) else catalog.searchFoods(q, lo, hi, PAGE, page * PAGE)
    }

    fun foodsBySlugs(slugs: List<String>): Flow<List<CatalogFood>> = catalog.foodsBySlugs(slugs)

    fun servings(foodSlug: String): Flow<List<CatalogServing>> = catalog.servings(foodSlug)

    /** True once the first catalog download delivered anything (lets the UI skip a "downloading" state). */
    suspend fun catalogReady(): Boolean = catalog.exerciseCount() > 0 && catalog.foodCount() > 0

}
