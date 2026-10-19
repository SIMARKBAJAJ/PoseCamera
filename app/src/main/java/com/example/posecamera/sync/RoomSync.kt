package com.example.posecamera.sync

import com.example.posecamera.data.AppDatabase
import com.example.posecamera.data.LocalFoodLog
import com.example.posecamera.data.LocalPlanCompletion
import com.example.posecamera.data.LocalProfile
import com.example.posecamera.data.LocalSet
import com.example.posecamera.data.LocalUserPlan
import com.example.posecamera.data.LocalWeight
import com.example.posecamera.data.SyncState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.util.UUID

private val gson = Gson()
private val longMap = object : TypeToken<Map<String, Long>>() {}.type
private val progressMap = object : TypeToken<Map<String, CatalogProgress>>() {}.type
private val stringMap = object : TypeToken<Map<String, String>>() {}.type

private fun AppDatabase.state(): SyncState = sync().state()
    ?: SyncState(deviceId = "android-" + UUID.randomUUID().toString().take(8)).also { sync().putState(it) }

private fun AppDatabase.updateState(f: (SyncState) -> SyncState) = runInTransaction { sync().putState(f(state())) }

/** Blocking by design: called only from SyncWorker / first-run code on Dispatchers.IO. */
class RoomSyncStore(private val db: AppDatabase) : SyncStore {
    private val dao = db.sync()

    val deviceId: String get() = db.state().deviceId

    override fun pendingOps(limit: Int) = dao.pending(limit).map { it.toOp() }

    /** Removes ops and marks a row synced once its last pending op is gone. */
    override fun removeOps(opIds: List<String>) = db.runInTransaction {
        val keys = dao.keysOf(opIds).distinct()
        dao.deleteOps(opIds)
        keys.filterNot { dao.hasPending(it.entity, it.clientId) }.forEach { k ->
            when (k.entity) {
                E_SETS -> dao.markSet(k.clientId)
                E_FOOD -> dao.markFoodLog(k.clientId)
                E_WEIGHT -> dao.markWeight(k.clientId)
                E_PROFILE -> dao.markProfile(k.clientId)
                E_PLAN -> dao.markUserPlan(k.clientId)
                E_COMPLETIONS -> dao.markPlanCompletion(k.clientId)
            }
        }
    }

    override fun markRejected(opIds: List<String>) = db.runInTransaction {
        dao.keysOf(opIds).filter { it.entity == E_SETS }.forEach { dao.markSetFailed(it.clientId) }
    }

    override fun recordFailure(opIds: List<String>, error: String) = dao.failOps(opIds, error.take(300))

    override fun hasPendingOp(entity: String, clientId: String) = dao.hasPending(entity, clientId)

    override fun applyUpsert(entity: String, clientId: String, data: String) {
        when (val row = rowFromPull(entity, clientId, data, System.currentTimeMillis())) {
            is LocalSet -> dao.upsert(row)
            is LocalFoodLog -> dao.upsert(row)
            is LocalWeight -> dao.upsert(row)
            is LocalProfile -> dao.upsert(row)
            is LocalUserPlan -> dao.upsert(row)
            is LocalPlanCompletion -> dao.upsert(row)
        }
    }

    override fun applyDelete(entity: String, clientId: String) {
        when (entity) {
            E_SETS -> dao.deleteSet(clientId)
            E_FOOD -> dao.deleteFoodLog(clientId)
            E_WEIGHT -> dao.deleteWeight(clientId)
            E_PROFILE -> dao.deleteProfile(clientId)
            E_PLAN -> dao.deleteUserPlan(clientId)
            E_COMPLETIONS -> dao.deletePlanCompletion(clientId)
        }
    }

    override var cursor: Long
        get() = db.state().cursor
        set(v) = db.updateState { it.copy(cursor = v) }

    override var catalogVersions: Map<String, Long>
        get() = gson.fromJson(db.state().catalogVersions, longMap)
        set(v) = db.updateState { it.copy(catalogVersions = gson.toJson(v)) }
}

class RoomCatalogSink(private val db: AppDatabase) : CatalogSink {
    private val dao = db.catalog()

    override fun progress(entity: String): CatalogProgress? =
        gson.fromJson<Map<String, CatalogProgress>>(db.state().catalogProgress, progressMap)[entity]

    override fun etag(entity: String, offset: Int, limit: Int): String? =
        gson.fromJson<Map<String, String>>(db.state().catalogEtags, stringMap)["$entity:$offset:$limit"]

    override fun insert(entity: String, items: List<JsonObject>) = db.runInTransaction {
        when (entity) {
            "exercises" -> dao.upsertExercises(items.map(::exerciseRow))
            "machines" -> dao.upsertMachines(items.map(::machineRow))
            "muscles" -> dao.upsertMuscles(items.map(::muscleRow))
            "foods" -> dao.upsertFoodsWithServings(items.map(::foodRow), items.flatMap(::servingRows))
            "plans" -> dao.upsertPlans(items.map(::planRow))
        }
    }

    override fun pageDone(entity: String, progress: CatalogProgress, limit: Int, etag: String?) {
        db.updateState { s ->
            val p = gson.fromJson<Map<String, CatalogProgress>>(s.catalogProgress, progressMap)
            val e = gson.fromJson<Map<String, String>>(s.catalogEtags, stringMap).toMutableMap()
            // the ETag names the page that was just stored: the one that ends where progress.offset begins
            if (etag != null) e["$entity:${progress.offset - limit}:$limit"] = etag
            s.copy(catalogProgress = gson.toJson(p + (entity to progress)), catalogEtags = gson.toJson(e))
        }
    }

    override fun finish(entity: String) {
        db.updateState { s ->
            val p = gson.fromJson<Map<String, CatalogProgress>>(s.catalogProgress, progressMap) - entity
            val e = gson.fromJson<Map<String, String>>(s.catalogEtags, stringMap).filterKeys { !it.startsWith("$entity:") }
            s.copy(catalogProgress = gson.toJson(p), catalogEtags = gson.toJson(e))
        }
    }
}
