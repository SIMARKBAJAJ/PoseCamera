package com.example.posecamera.ui.train

import com.example.posecamera.data.LocalSet
import com.example.posecamera.lifts.Lifts
import com.example.posecamera.sync.SyncOutcome
import com.example.posecamera.sync.SyncUi
import com.example.posecamera.ui.design.SyncPillState
import com.example.posecamera.ui.design.syncPillState
import com.example.posecamera.ui.home.prsOf
import com.example.posecamera.ui.home.validSets
import com.example.posecamera.ui.library.parseMuscles
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectedAndPlatesTest {
    private fun set(reps: Int, ts: Long, failed: Boolean = false, kg: Double? = 50.0) =
        LocalSet("c$ts", "other", "barbell-squat", reps, null, null, null, kg, null, null, ts, ts, true, failed)

    @Test fun platesOnlyForTheSixBarLifts() {
        val shown = Lifts.all.map { it.catalogSlug }.filter(::showsPlates).toSet()
        assertEquals(
            setOf("barbell-squat", "barbell-bench-press-medium-grip", "barbell-deadlift", "romanian-deadlift", "standing-military-press", "bent-over-barbell-row"),
            shown,
        )
        listOf("pullups", "plank", "crunches", "dumbbell-step-ups", "side-lateral-raise", "dumbbell-bicep-curl", "barbell-glute-bridge").forEach { assertFalse(it, showsPlates(it)) }
    }

    @Test fun zeroRepAndRejectedSetsAreExcludedEverywhere() {
        val good = set(5, 1_000); val zero = set(0, 2_000); val rejected = set(8, 3_000, failed = true)
        val all = listOf(good, zero, rejected)
        assertEquals(listOf(good), validSets(all))
        assertTrue(prsOf(all + set(6, 4_000, kg = 50.0)).none { it.timestamp == 3_000L })
        val day = 86_400_000L
        val prev = previousSets(listOf(set(0, 1_000), set(5, 2_000, failed = true), good.copy(clientId = "g", timestamp = 3_000)), "barbell-squat", 3 * day, TimeZone.getTimeZone("UTC"))
        assertEquals(listOf(3_000L), prev.map { it.timestamp })
        assertEquals(listOf(5), setsToday(all, "barbell-squat", 5_000, TimeZone.getTimeZone("UTC")).map { it.repCount })
    }

    @Test fun pillLabelFollowsOneStatusSource() {
        assertEquals("Syncing", syncPillState(SyncUi(running = true)).label)
        assertEquals("Synced", syncPillState(SyncUi(lastOutcome = SyncOutcome.OK)).label)
        assertEquals("Not synced", syncPillState(SyncUi()).label)
        assertEquals(SyncPillState.LOGIN, syncPillState(SyncUi(needsLogin = true)))
    }

    @Test fun nonDemoMusclesStillHighlight() {
        assertEquals(setOf("lats"), parseMuscles("Middle Back"))
        assertEquals(setOf("lower_back", "glutes"), parseMuscles("Lower Back, Glutes"))
    }
}
