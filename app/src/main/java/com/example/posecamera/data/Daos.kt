package com.example.posecamera.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Sync-side access. Plain (blocking) functions: only SyncWorker / first-run code calls them, always on
 * Dispatchers.IO (Room refuses the main thread). Each @Transaction function is one atomic unit.
 */
@Dao
abstract class SyncDao {
    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :limit")
    abstract fun pending(limit: Int): List<Outbox>

    @Query("DELETE FROM outbox WHERE opId IN (:opIds)")
    abstract fun deleteOps(opIds: List<String>)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE opId IN (:opIds)")
    abstract fun failOps(opIds: List<String>, error: String)

    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE entity = :entity AND clientId = :clientId)")
    abstract fun hasPending(entity: String, clientId: String): Boolean

    @Query("SELECT entity, clientId FROM outbox WHERE opId IN (:opIds)")
    abstract fun keysOf(opIds: List<String>): List<EntityKey>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    abstract fun state(): SyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putState(state: SyncState)

    @Query("SELECT COUNT(*) FROM outbox")
    abstract fun pendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox")
    abstract fun pendingCount(): Int

    // applying server rows (idempotent: replace by client_id)
    @Upsert abstract fun upsert(row: LocalSet)
    @Upsert abstract fun upsert(row: LocalFoodLog)
    @Upsert abstract fun upsert(row: LocalWeight)
    @Upsert abstract fun upsert(row: LocalProfile)
    @Upsert abstract fun upsert(row: LocalUserPlan)
    @Upsert abstract fun upsert(row: LocalPlanCompletion)

    @Query("DELETE FROM local_sets WHERE clientId = :id") abstract fun deleteSet(id: String)
    @Query("DELETE FROM local_food_logs WHERE clientId = :id") abstract fun deleteFoodLog(id: String)
    @Query("DELETE FROM local_weights WHERE clientId = :id") abstract fun deleteWeight(id: String)
    @Query("DELETE FROM local_profile WHERE clientId = :id") abstract fun deleteProfile(id: String)
    @Query("DELETE FROM local_user_plan WHERE clientId = :id") abstract fun deleteUserPlan(id: String)
    @Query("DELETE FROM local_plan_completions WHERE clientId = :id") abstract fun deletePlanCompletion(id: String)

    @Query("UPDATE local_sets SET synced = 1 WHERE clientId = :id") abstract fun markSet(id: String)
    @Query("UPDATE local_sets SET syncFailed = 1 WHERE clientId = :id") abstract fun markSetFailed(id: String)
    @Query("UPDATE local_food_logs SET synced = 1 WHERE clientId = :id") abstract fun markFoodLog(id: String)
    @Query("UPDATE local_weights SET synced = 1 WHERE clientId = :id") abstract fun markWeight(id: String)
    @Query("UPDATE local_profile SET synced = 1 WHERE clientId = :id") abstract fun markProfile(id: String)
    @Query("UPDATE local_user_plan SET synced = 1 WHERE clientId = :id") abstract fun markUserPlan(id: String)
    @Query("UPDATE local_plan_completions SET synced = 1 WHERE clientId = :id") abstract fun markPlanCompletion(id: String)
}

data class EntityKey(val entity: String, val clientId: String)

/** Catalog cache. Chunk writes are @Transaction so a crash leaves whole chunks only. */
@Dao
abstract class CatalogDao {
    @Upsert abstract fun upsertExercises(rows: List<CatalogExercise>)
    @Upsert abstract fun upsertMachines(rows: List<CatalogMachine>)
    @Upsert abstract fun upsertMuscles(rows: List<CatalogMuscle>)
    @Upsert abstract fun upsertFoods(rows: List<CatalogFood>)
    @Upsert abstract fun upsertServings(rows: List<CatalogServing>)
    @Upsert abstract fun upsertPlans(rows: List<CatalogPlan>)

    @Query("DELETE FROM catalog_servings WHERE foodSlug IN (:slugs)")
    abstract fun deleteServings(slugs: List<String>)

    @Transaction
    open fun upsertFoodsWithServings(foods: List<CatalogFood>, servings: List<CatalogServing>) {
        deleteServings(foods.map { it.slug })
        upsertFoods(foods)
        upsertServings(servings)
    }

    // Name search: prefix (index range lo <= search < hi) OR a word starting with q. q is pre-sanitised (no % _ \).
    @Query(
        "SELECT * FROM catalog_foods WHERE (search >= :lo AND search < :hi) OR search LIKE '% ' || :q || '%' " +
            "ORDER BY (search >= :lo AND search < :hi) DESC, search, slug LIMIT :limit OFFSET :offset",
    )
    abstract fun searchFoods(q: String, lo: String, hi: String, limit: Int, offset: Int): Flow<List<CatalogFood>>

    @Query("SELECT * FROM catalog_foods ORDER BY search, slug LIMIT :limit OFFSET :offset")
    abstract fun foods(limit: Int, offset: Int): Flow<List<CatalogFood>>

    @Query("SELECT * FROM catalog_foods WHERE slug IN (:slugs)")
    abstract fun foodsBySlugs(slugs: List<String>): Flow<List<CatalogFood>>

    @Query("SELECT * FROM catalog_servings WHERE foodSlug = :slug ORDER BY grams")
    abstract fun servings(slug: String): Flow<List<CatalogServing>>

    @Query(
        "SELECT * FROM catalog_exercises WHERE (search >= :lo AND search < :hi) OR search LIKE '% ' || :q || '%' " +
            "ORDER BY (search >= :lo AND search < :hi) DESC, search, slug LIMIT :limit OFFSET :offset",
    )
    abstract fun searchExercises(q: String, lo: String, hi: String, limit: Int, offset: Int): Flow<List<CatalogExercise>>

    @Query("SELECT * FROM catalog_exercises ORDER BY search, slug LIMIT :limit OFFSET :offset")
    abstract fun exercises(limit: Int, offset: Int): Flow<List<CatalogExercise>>

    // Library browse: optional name query (q = '' matches all) AND optional muscle substring ('' = any), first :limit rows.
    @Query(
        "SELECT * FROM catalog_exercises WHERE (:q = '' OR (search >= :lo AND search < :hi) OR search LIKE '% ' || :q || '%') " +
            "AND (:muscle = '' OR (LOWER(primaryMuscles) LIKE '%' || :muscle || '%' AND (:n1 = '' OR search LIKE '%' || :n1 || '%' " +
            "OR (:n2 <> '' AND search LIKE '%' || :n2 || '%') OR (:n3 <> '' AND search LIKE '%' || :n3 || '%')))) " +
            "ORDER BY (:q <> '' AND search >= :lo AND search < :hi) DESC, search, slug LIMIT :limit",
    )
    abstract fun browseExercises(
        q: String, lo: String, hi: String, muscle: String, n1: String, n2: String, n3: String, limit: Int,
    ): Flow<List<CatalogExercise>>

    @Query("SELECT * FROM catalog_exercises WHERE slug IN (:slugs)")
    abstract fun exercisesBySlugs(slugs: List<String>): Flow<List<CatalogExercise>>

    @Query("SELECT * FROM catalog_plans WHERE slug = :slug")
    abstract fun plan(slug: String): Flow<CatalogPlan?>

    @Query("SELECT * FROM catalog_plans ORDER BY daysPerWeek, name")
    abstract fun plans(): Flow<List<CatalogPlan>>

    @Query("SELECT COUNT(*) FROM catalog_exercises")
    abstract suspend fun exerciseCount(): Int

    @Query("SELECT COUNT(*) FROM catalog_foods")
    abstract suspend fun foodCount(): Int
}

/** UI-facing user data. Every write is one transaction: row + outbox op. */
@Dao
abstract class UserDao {
    @Insert abstract suspend fun insertOp(op: Outbox)

    @Upsert abstract suspend fun put(row: LocalSet)
    @Upsert abstract suspend fun put(row: LocalFoodLog)
    @Upsert abstract suspend fun put(row: LocalWeight)
    @Upsert abstract suspend fun put(row: LocalProfile)
    @Upsert abstract suspend fun put(row: LocalUserPlan)
    @Upsert abstract suspend fun put(row: LocalPlanCompletion)

    @Query("DELETE FROM local_food_logs WHERE clientId = :id") abstract suspend fun removeFoodLog(id: String)
    @Query("DELETE FROM local_weights WHERE clientId = :id") abstract suspend fun removeWeight(id: String)

    @Transaction open suspend fun save(row: LocalSet, op: Outbox) { put(row); insertOp(op) }
    @Transaction open suspend fun save(row: LocalFoodLog, op: Outbox) { put(row); insertOp(op) }
    @Transaction open suspend fun save(row: LocalWeight, op: Outbox) { put(row); insertOp(op) }
    @Transaction open suspend fun save(row: LocalProfile, op: Outbox) { put(row); insertOp(op) }
    @Transaction open suspend fun save(row: LocalUserPlan, op: Outbox) { put(row); insertOp(op) }
    @Transaction open suspend fun save(row: LocalPlanCompletion, op: Outbox) { put(row); insertOp(op) }
    @Transaction open suspend fun deleteFoodLog(id: String, op: Outbox) { removeFoodLog(id); insertOp(op) }
    @Transaction open suspend fun deleteWeight(id: String, op: Outbox) { removeWeight(id); insertOp(op) }

    @Query("SELECT * FROM local_sets ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    abstract fun sets(limit: Int, offset: Int): Flow<List<LocalSet>>

    @Query("SELECT * FROM local_sets WHERE exerciseSlug IN (:slugs) OR (exerciseSlug IS NULL AND exerciseType IN ('squat','push-up'))")
    abstract fun skillSets(slugs: List<String>): Flow<List<LocalSet>>

    @Query("SELECT timestamp FROM local_sets")
    abstract fun setTimestamps(): Flow<List<Long>>

    @Query("SELECT * FROM local_food_logs WHERE date = :date ORDER BY updatedAt")
    abstract fun foodLogs(date: String): Flow<List<LocalFoodLog>>

    @Query("SELECT * FROM local_weights ORDER BY date DESC LIMIT :limit OFFSET :offset")
    abstract fun weights(limit: Int, offset: Int): Flow<List<LocalWeight>>

    @Query("SELECT * FROM local_weights WHERE date = :date LIMIT 1")
    abstract suspend fun weightOn(date: String): LocalWeight?

    @Query("SELECT * FROM local_profile WHERE clientId = 'profile'")
    abstract fun profile(): Flow<LocalProfile?>

    @Query("SELECT * FROM local_user_plan WHERE clientId = 'plan'")
    abstract fun userPlan(): Flow<LocalUserPlan?>

    @Query("SELECT * FROM local_plan_completions ORDER BY date DESC LIMIT :limit OFFSET :offset")
    abstract fun planCompletions(limit: Int, offset: Int): Flow<List<LocalPlanCompletion>>

    @Query("SELECT * FROM local_plan_completions WHERE date = :date LIMIT 1")
    abstract suspend fun completionOn(date: String): LocalPlanCompletion?
}
