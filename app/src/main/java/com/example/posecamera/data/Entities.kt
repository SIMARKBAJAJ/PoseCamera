package com.example.posecamera.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ---- catalog cache (read-only on the phone, replaced from /api/catalog/export) ----
// `search` = lower-case name (+ local name); queried by prefix range and by " token" LIKE. `json` = the full
// server record, so fields the UI does not query yet are not lost.

@Entity(tableName = "catalog_exercises", indices = [Index("search")])
data class CatalogExercise(
    @PrimaryKey val slug: String,
    val name: String,
    val search: String,
    val primaryMuscles: String, // comma separated
    val machine: String?,
    val movementPattern: String?,
    val difficulty: String?,
    val hasPoseTracking: Boolean,
    val verified: Boolean,
    val json: String,
)

@Entity(tableName = "catalog_machines")
data class CatalogMachine(@PrimaryKey val name: String, val type: String?, val json: String)

@Entity(tableName = "catalog_muscles")
data class CatalogMuscle(@PrimaryKey val name: String)

@Entity(tableName = "catalog_foods", indices = [Index("search"), Index("category")])
data class CatalogFood(
    @PrimaryKey val slug: String,
    val name: String,
    val nameLocal: String?,
    val search: String,
    val category: String?,
    val region: String?,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val verified: Boolean,
    val json: String,
)

@Entity(tableName = "catalog_servings", primaryKeys = ["foodSlug", "label"])
data class CatalogServing(val foodSlug: String, val label: String, val serverId: Long?, val grams: Double)

@Entity(tableName = "catalog_plans")
data class CatalogPlan(
    @PrimaryKey val slug: String,
    val name: String,
    val level: String?,
    val goal: String?,
    val daysPerWeek: Int,
    val split: String?,
    val json: String,
)

// ---- user data: client_id = uuid (or "profile"/"plan"), synced=false while an outbox op is pending ----

@Entity(tableName = "local_sets", indices = [Index("timestamp")])
data class LocalSet(
    @PrimaryKey val clientId: String,
    val exerciseType: String, // squat | push-up | other
    val exerciseSlug: String?,
    val repCount: Int,
    val cleanCount: Int?,
    val attemptedCount: Int?,
    val formscore: Float?,
    val loadKg: Double?,
    val rpe: Double?,
    val setType: String?,
    val timestamp: Long, // epoch ms
    val updatedAt: Long,
    val synced: Boolean,
    /** The server permanently rejected this set's push; it will never sync. */
    @ColumnInfo(defaultValue = "0") val syncFailed: Boolean = false,
)

@Entity(tableName = "local_food_logs", indices = [Index("date")])
data class LocalFoodLog(
    @PrimaryKey val clientId: String,
    val meal: String?,
    val date: String, // yyyy-MM-dd
    val foodSlug: String?,
    val servingId: Long?,
    val quantityG: Double?,
    val name: String?,
    val kcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val computedJson: String?, // server-frozen macros from pull; null while pending (UI derives from catalog)
    val updatedAt: Long,
    val synced: Boolean,
)

@Entity(tableName = "local_weights", indices = [Index("date")])
data class LocalWeight(
    @PrimaryKey val clientId: String,
    val kg: Double,
    val date: String,
    val updatedAt: Long,
    val synced: Boolean,
)

/** One row, clientId "profile". */
@Entity(tableName = "local_profile")
data class LocalProfile(
    @PrimaryKey val clientId: String = PROFILE_ID,
    val sex: String?,
    val birthYear: Int?,
    val heightCm: Double?,
    val weightKg: Double?,
    val activityLevel: String?,
    val goal: String?,
    val dietPref: String?,
    val updatedAt: Long,
    val synced: Boolean,
)

/** One row, clientId "plan". */
@Entity(tableName = "local_user_plan")
data class LocalUserPlan(
    @PrimaryKey val clientId: String = PLAN_ID,
    val slug: String,
    val startDate: String,
    val equipment: String?,
    val updatedAt: Long,
    val synced: Boolean,
)

@Entity(tableName = "local_plan_completions", indices = [Index("date")])
data class LocalPlanCompletion(
    @PrimaryKey val clientId: String,
    val dayIndex: Int,
    val date: String,
    val updatedAt: Long,
    val synced: Boolean,
)

const val PROFILE_ID = "profile"
const val PLAN_ID = "plan"

// ---- sync bookkeeping ----

/** `seq` gives a stable push order; opId is the server's idempotency key and never changes on retry. */
@Entity(tableName = "outbox", indices = [Index("opId", unique = true), Index("entity", "clientId")])
data class Outbox(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String,
    val entity: String,
    val clientId: String,
    val action: String,
    val payload: String,
    val createdAt: Long, // epoch ms, sent as client_updated_at (the LWW clock)
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: Int = 1,
    val cursor: Long = 0,
    val catalogVersions: String = "{}", // JSON entity -> version fully downloaded
    val deviceId: String,
    val catalogEtags: String = "{}", // JSON "entity:offset:limit" -> ETag of that page
    val catalogProgress: String = "{}", // JSON entity -> {"version":v,"offset":n} of an unfinished download
)
