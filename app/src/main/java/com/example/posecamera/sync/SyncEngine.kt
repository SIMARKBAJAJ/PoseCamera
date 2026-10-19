package com.example.posecamera.sync

import com.example.posecamera.power.PowerState
import com.example.posecamera.power.THERMAL_SEVERE
import com.example.posecamera.power.Tier
import com.example.posecamera.power.tierOf
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

const val PUSH_BATCH = 100
const val PULL_LIMIT = 500

data class OutboxOp(
    val opId: String,
    val entity: String,
    val clientId: String,
    val action: String, // "upsert" | "delete"
    val payload: String,
    val clientUpdatedAt: Long,
)

enum class PushStatus { APPLIED, DUPLICATE, CONFLICT, REJECTED }

data class PushResult(val opId: String, val status: PushStatus, val error: String? = null)

data class Change(val cursor: Long, val entity: String, val clientId: String, val op: String, val data: String?)

data class PullPage(val changes: List<Change>, val nextCursor: Long, val hasMore: Boolean)

/** Thrown by the I/O layer on HTTP 401. */
class UnauthorizedException : Exception("401")

/** Room-backed in production. Each method is one transaction. */
interface SyncStore {
    fun pendingOps(limit: Int): List<OutboxOp>
    fun removeOps(opIds: List<String>)
    fun recordFailure(opIds: List<String>, error: String)
    fun hasPendingOp(entity: String, clientId: String): Boolean

    /** Op ids the server permanently REJECTED; called before they are removed, so their rows can still be looked up. */
    fun markRejected(opIds: List<String>) {}
    fun applyUpsert(entity: String, clientId: String, data: String) // idempotent: replace by client_id
    fun applyDelete(entity: String, clientId: String) // idempotent tombstone
    var cursor: Long
    var catalogVersions: Map<String, Long>
}

interface SyncApi {
    fun push(ops: List<OutboxOp>): List<PushResult>
    fun pull(cursor: Long, limit: Int): PullPage
    fun catalogVersions(): Map<String, Long>

    /** Streams one entity export (ETag/gzip) into the store; must not buffer the whole body. */
    fun downloadCatalog(entity: String)
}

enum class SyncOutcome { OK, PAUSED_AUTH, DEFERRED_POWER, RETRY }

/** Never sync while thermal >= SEVERE, even on user request. */
fun syncAllowed(p: PowerState): Boolean = p.thermal < THERMAL_SEVERE

/** Periodic catalog download is skipped on the CRITICAL tier (low battery not charging, or battery saver+low). */
fun catalogAllowed(p: PowerState, userInitiated: Boolean): Boolean =
    syncAllowed(p) && (userInitiated || tierOf(p) != Tier.CRITICAL)

/** 30 s base, doubling, 1 h cap; jitter keeps the result in [d/2, d). */
fun backoffMs(attempt: Int, rnd: Random = Random.Default): Long {
    val d = min(3_600_000L, 30_000L shl min(attempt.coerceAtLeast(0), 7))
    return d / 2 + rnd.nextLong(d / 2)
}

class SyncEngine(private val store: SyncStore, private val api: SyncApi) {
    fun run(power: PowerState, userInitiated: Boolean = false): SyncOutcome {
        if (!syncAllowed(power)) return SyncOutcome.DEFERRED_POWER
        return try {
            push()
            pull()
            if (catalogAllowed(power, userInitiated)) refreshCatalog()
            SyncOutcome.OK
        } catch (e: UnauthorizedException) {
            SyncOutcome.PAUSED_AUTH // outbox untouched
        } catch (e: IOException) {
            SyncOutcome.RETRY
        }
    }

    private fun push() {
        while (true) {
            val batch = store.pendingOps(PUSH_BATCH)
            if (batch.isEmpty()) return
            val results = try {
                api.push(batch)
            } catch (e: IOException) {
                store.recordFailure(batch.map { it.opId }, e.message ?: "io")
                throw e
            }
            val done = results.map { it.opId }.toSet()
            results.filter { it.status == PushStatus.REJECTED }.map { it.opId }
                .takeIf { it.isNotEmpty() }?.let(store::markRejected)
            // All four statuses are terminal; CONFLICT = server wins via the pull that follows.
            store.removeOps(batch.map { it.opId }.filter { it in done })
            val missing = batch.map { it.opId }.filter { it !in done }
            if (missing.isNotEmpty()) {
                store.recordFailure(missing, "no result")
                throw IOException("server omitted ${missing.size} results")
            }
        }
    }

    private fun pull() {
        do {
            val page = api.pull(store.cursor, PULL_LIMIT)
            for (c in page.changes) {
                if (store.hasPendingOp(c.entity, c.clientId)) continue // local pending op is newer; it will push
                if (c.op == "delete" || c.data == null) store.applyDelete(c.entity, c.clientId)
                else store.applyUpsert(c.entity, c.clientId, c.data)
            }
            store.cursor = page.nextCursor
        } while (page.hasMore)
    }

    private fun refreshCatalog() {
        for ((entity, v) in api.catalogVersions()) {
            if (store.catalogVersions[entity] == v) continue
            api.downloadCatalog(entity)
            store.catalogVersions = store.catalogVersions + (entity to v) // per entity, so failures resume
        }
    }
}
