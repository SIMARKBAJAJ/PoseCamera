package com.example.posecamera.ui.design

import com.example.posecamera.sync.SyncOutcome
import com.example.posecamera.sync.SyncUi
import com.example.posecamera.ui.design.g3d.Cam3
import com.example.posecamera.ui.design.g3d.P3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SyncPillStateTest {
    @Test fun neverRanIsNeutralNotOffline() = assertEquals(SyncPillState.NOT_SYNCED, syncPillState(SyncUi()))
    @Test fun okIsSynced() = assertEquals(SyncPillState.SYNCED, syncPillState(SyncUi(lastOutcome = SyncOutcome.OK)))
    @Test fun failureIsOffline() {
        assertEquals(SyncPillState.OFFLINE, syncPillState(SyncUi(lastOutcome = SyncOutcome.RETRY)))
        assertEquals(SyncPillState.OFFLINE, syncPillState(SyncUi(lastOutcome = SyncOutcome.DEFERRED_POWER)))
    }
    @Test fun authIsLogIn() {
        assertEquals(SyncPillState.LOGIN, syncPillState(SyncUi(lastOutcome = SyncOutcome.PAUSED_AUTH)))
        assertEquals(SyncPillState.LOGIN, syncPillState(SyncUi(needsLogin = true, lastOutcome = SyncOutcome.OK)))
    }
    @Test fun runningWins() = assertEquals(SyncPillState.SYNCING, syncPillState(SyncUi(running = true)))

    @Test fun projectReusesOutStruct() {
        val out = P3()
        val a = Cam3(0.4f, 0.2f, 3f).project(1f, 2f, 0.5f)
        assertSame(out, Cam3(0.4f, 0.2f, 3f).project(1f, 2f, 0.5f, out))
        assertEquals(a.sx, out.sx, 1e-6f); assertEquals(a.depth, out.depth, 1e-6f)
    }
}
