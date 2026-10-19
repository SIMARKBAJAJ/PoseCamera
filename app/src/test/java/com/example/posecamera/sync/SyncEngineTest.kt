package com.example.posecamera.sync

import com.example.posecamera.power.PowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

private class FakeStore : SyncStore {
    val outbox = mutableListOf<OutboxOp>()
    val rows = mutableMapOf<Pair<String, String>, String>()
    val errors = mutableListOf<String>()
    override var cursor = 0L
    override var catalogVersions: Map<String, Long> = emptyMap()
    override fun pendingOps(limit: Int) = outbox.take(limit)
    override fun removeOps(opIds: List<String>) { outbox.removeAll { it.opId in opIds } }
    override fun recordFailure(opIds: List<String>, error: String) { errors += error }
    override fun hasPendingOp(entity: String, clientId: String) = outbox.any { it.entity == entity && it.clientId == clientId }
    override fun applyUpsert(entity: String, clientId: String, data: String) { rows[entity to clientId] = data }
    override fun applyDelete(entity: String, clientId: String) { rows.remove(entity to clientId) }
}

private class FakeApi : SyncApi {
    var status = PushStatus.APPLIED
    val pushed = mutableListOf<Int>()
    val pages = mutableListOf<PullPage>()
    var versions = mapOf("foods" to 1L)
    val downloads = mutableListOf<String>()
    var fail: Exception? = null
    override fun push(ops: List<OutboxOp>): List<PushResult> {
        fail?.let { throw it }
        pushed += ops.size
        return ops.map { PushResult(it.opId, status) }
    }
    override fun pull(cursor: Long, limit: Int): PullPage {
        fail?.let { throw it }
        return if (pages.isEmpty()) PullPage(emptyList(), cursor, false) else pages.removeAt(0)
    }
    override fun catalogVersions() = versions
    override fun downloadCatalog(entity: String) { downloads += entity }
}

private fun op(i: Int, e: String = "food_logs", c: String = "c$i") = OutboxOp("o$i", e, c, "upsert", "{}", i.toLong())
private val ok = PowerState(80, false, false, 0)

class SyncEngineTest {
    private val s = FakeStore()
    private val a = FakeApi()
    private val e = SyncEngine(s, a)

    @Test fun pushBatchesOf100AndClearsOutbox() {
        repeat(250) { s.outbox += op(it) }
        assertEquals(SyncOutcome.OK, e.run(ok))
        assertEquals(listOf(100, 100, 50), a.pushed)
        assertTrue(s.outbox.isEmpty())
    }

    @Test fun everyStatusIsTerminal() {
        for (st in PushStatus.entries) {
            s.outbox.clear(); s.outbox += op(1); a.status = st
            e.run(ok)
            assertTrue("$st", s.outbox.isEmpty())
        }
    }

    @Test fun ioFailureKeepsOutboxAndRetries() {
        s.outbox += op(1); a.fail = IOException("down")
        assertEquals(SyncOutcome.RETRY, e.run(ok))
        assertEquals(1, s.outbox.size)
        assertEquals(listOf("down"), s.errors)
    }

    @Test fun unauthorizedPausesWithoutDroppingOutbox() {
        s.outbox += op(1); a.fail = UnauthorizedException()
        assertEquals(SyncOutcome.PAUSED_AUTH, e.run(ok))
        assertEquals(1, s.outbox.size)
    }

    @Test fun pullIsIdempotentAndAppliesTombstones() {
        val up = Change(1, "weight_logs", "w1", "upsert", "{\"kg\":70}")
        val del = Change(2, "weight_logs", "w2", "delete", null)
        s.rows["weight_logs" to "w2"] = "x"
        a.pages += PullPage(listOf(up, up), 2, true)
        a.pages += PullPage(listOf(del, del), 2, false)
        e.run(ok)
        assertEquals(mapOf(("weight_logs" to "w1") to "{\"kg\":70}"), s.rows)
        assertEquals(2L, s.cursor)
    }

    @Test fun localPendingOpBeatsServerChangeThenConflictTakesServer() {
        val change = Change(1, "profile", "p", "upsert", "{\"v\":2}")
        s.outbox += op(1, "profile", "p")
        a.fail = null
        a.status = PushStatus.CONFLICT
        // Offline push: op stays pending, so the pulled change must be ignored.
        val offline = object : SyncApi by a {
            override fun push(ops: List<OutboxOp>): List<PushResult> = throw IOException()
        }
        a.pages += PullPage(listOf(change), 1, false)
        SyncEngine(s, offline).run(ok)
        assertTrue(s.rows.isEmpty())
        // Online: server answers CONFLICT, op dropped, same-run pull applies server state.
        a.pages += PullPage(listOf(change), 1, false)
        e.run(ok)
        assertEquals("{\"v\":2}", s.rows["profile" to "p"])
    }

    @Test fun catalogOnlyWhenVersionDiffers() {
        e.run(ok)
        assertEquals(listOf("foods"), a.downloads)
        assertEquals(mapOf("foods" to 1L), s.catalogVersions)
        e.run(ok)
        assertEquals(1, a.downloads.size)
        a.versions = mapOf("foods" to 2L)
        e.run(ok)
        assertEquals(2, a.downloads.size)
    }

    @Test fun powerGating() {
        s.outbox += op(1)
        val hot = PowerState(90, true, false, 3)
        assertEquals(SyncOutcome.DEFERRED_POWER, e.run(hot, userInitiated = true))
        assertEquals(1, s.outbox.size)
        val critical = PowerState(10, false, false, 0)
        assertEquals(SyncOutcome.OK, e.run(critical))
        assertTrue(a.downloads.isEmpty())
        e.run(critical, userInitiated = true)
        assertEquals(1, a.downloads.size)
    }

    @Test fun backoffGrowsJitteredAndCapped() {
        val r = Random(1)
        for (n in 0..20) {
            val full = minOf(3_600_000L, 30_000L shl minOf(n, 7))
            val d = backoffMs(n, r)
            assertTrue(d >= full / 2 && d < full)
        }
        assertTrue(backoffMs(7, Random(2)) > backoffMs(0, Random(2)))
    }
}
