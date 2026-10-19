package com.example.posecamera.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo.State
import com.example.posecamera.pose.ExerciseType
import com.example.posecamera.pose.RepSetSummary
import com.example.posecamera.power.PowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun set(type: ExerciseType, attempted: Int, total: Int = attempted, hold: Float? = null) =
    RepSetSummary(type, attempted, attempted, 0, 100f, null, emptyList(), total, hold, emptyList(), "slug")

class ZeroRepRefusalTest {
    @Test fun everyTypeWithZeroRepsIsRefused() {
        for (t in ExerciseType.entries.filter { it != ExerciseType.PLANK }) {
            assertEquals(t.name, SaveResult.NO_REPS, set(t, 0).saveBlocker())
            assertNull(t.name, set(t, 0).toLocalSet("c", 0))
        }
    }

    @Test fun repsAreStoredAndPlankIsUnsupported() {
        assertNull(set(ExerciseType.SQUAT, 3).saveBlocker())
        assertNull(set(ExerciseType.LUNGE, 0, total = 4).saveBlocker())
        assertEquals(SaveResult.UNSUPPORTED, set(ExerciseType.PLANK, 0, hold = 30f).saveBlocker())
    }
}

class SyncPolicyTest {
    @Test fun needsLoginOnlyWhenATokenWasSent() {
        assertTrue(needsLoginAfter(SyncOutcome.PAUSED_AUTH, hadToken = true))
        assertEquals(false, needsLoginAfter(SyncOutcome.PAUSED_AUTH, hadToken = false))
        assertEquals(false, needsLoginAfter(SyncOutcome.OK, hadToken = true))
    }

    @Test fun pendingRunIsKeptOtherwiseOneTrailingRunIsAppended() {
        assertEquals(ExistingWorkPolicy.KEEP, syncPolicy(listOf(State.ENQUEUED)))
        assertEquals(ExistingWorkPolicy.KEEP, syncPolicy(listOf(State.RUNNING, State.ENQUEUED)))
        assertEquals(ExistingWorkPolicy.KEEP, syncPolicy(listOf(State.BLOCKED)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, syncPolicy(listOf(State.RUNNING)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, syncPolicy(emptyList()))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, syncPolicy(listOf(State.SUCCEEDED, State.FAILED)))
    }

    @Test fun accountSwitchDecision() {
        assertEquals(AccountSwitch.PROCEED, accountSwitch(null, "a", 5))
        assertEquals(AccountSwitch.PROCEED, accountSwitch("a", "a", 5))
        assertEquals(AccountSwitch.WIPE, accountSwitch("a", "b", 0))
        assertEquals(AccountSwitch.CONFIRM, accountSwitch("a", "b", 1))
    }
}

class RejectedPushTest {
    private class Store : SyncStore {
        val outbox = mutableListOf<OutboxOp>()
        val rejected = mutableListOf<String>()
        override var cursor = 0L
        override var catalogVersions: Map<String, Long> = emptyMap()
        override fun pendingOps(limit: Int) = outbox.take(limit)
        override fun removeOps(opIds: List<String>) { outbox.removeAll { it.opId in opIds } }
        override fun markRejected(opIds: List<String>) { rejected += opIds }
        override fun recordFailure(opIds: List<String>, error: String) {}
        override fun hasPendingOp(entity: String, clientId: String) = false
        override fun applyUpsert(entity: String, clientId: String, data: String) {}
        override fun applyDelete(entity: String, clientId: String) {}
    }

    @Test fun rejectedOpsAreReportedBeforeRemovalAndOthersAreNot() {
        val s = Store()
        s.outbox += OutboxOp("bad", E_SETS, "c1", "upsert", "{}", 1)
        s.outbox += OutboxOp("good", E_SETS, "c2", "upsert", "{}", 2)
        val api = object : SyncApi {
            override fun push(ops: List<OutboxOp>) =
                ops.map { PushResult(it.opId, if (it.opId == "bad") PushStatus.REJECTED else PushStatus.APPLIED) }
            override fun pull(cursor: Long, limit: Int) = PullPage(emptyList(), cursor, false)
            override fun catalogVersions() = emptyMap<String, Long>()
            override fun downloadCatalog(entity: String) {}
        }
        assertEquals(SyncOutcome.OK, SyncEngine(s, api).run(PowerState(80, false, false, 0)))
        assertEquals(listOf("bad"), s.rejected)
        assertTrue(s.outbox.isEmpty())
    }
}
