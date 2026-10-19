package com.example.posecamera.ui.home

import com.example.posecamera.data.LocalSet
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryUiTest {
    private val h = 3_600_000L
    private val utc = TimeZone.getTimeZone("UTC")
    private val t0 = 1_000 * 24 * h

    private fun set(slug: String?, type: String = "other") =
        LocalSet("id", type, slug, 5, null, null, null, null, null, null, t0, t0, true, false)

    private fun recovering(hours: Double) = MuscleRecovery(RecoveryState.RECOVERING, t0 + (hours * h).toLong(), hours)
    private val ready = MuscleRecovery(RecoveryState.READY, t0, null)
    private val never = MuscleRecovery(RecoveryState.NEVER, null, null)

    @Test fun uncuratedSlugStartsNoTimerByDefault() {
        val r = recoveryFor(List(3) { set("some-catalog-only-move") }, t0, RecoverySpeed.NORMAL, utc)
        assertTrue(r.values.all { it.state == RecoveryState.NEVER })
    }

    @Test fun catalogResolverFallsBackToPrimaryMuscles() {
        val resolve = catalogResolver(mapOf("some-catalog-only-move" to "Quadriceps,Glutes"))
        val r = recoveryFor(List(3) { set("some-catalog-only-move") }, t0, RecoverySpeed.NORMAL, utc, resolve)
        assertEquals(RecoveryState.RECOVERING, r.getValue("quads").state)
        assertEquals(RecoveryState.RECOVERING, r.getValue("glutes").state)
        assertEquals(RecoveryState.NEVER, r.getValue("chest").state)
    }

    @Test fun curatedTableWinsOverCatalog() {
        val resolve = catalogResolver(mapOf("crunches" to "Chest"))
        assertEquals(setOf("abs"), resolve("crunches")!!.keys)
    }

    @Test fun customResolverIsUsed() {
        val r = recoveryFor(listOf(set("x")), t0, RecoverySpeed.NORMAL, utc, { mapOf("chest" to 1f) })
        assertEquals(RecoveryState.RECOVERING, r.getValue("chest").state)
    }

    @Test fun fallbackMergesAndRejectsUnknown() {
        assertEquals(setOf("lats", "lower_back", "traps", "biceps"), catalogFallback("Back,Biceps")!!.keys)
        assertNull(catalogFallback("Spleen"))
        assertNull(catalogFallback(""))
    }

    @Test fun chipShowsMostRecoveringMax2() {
        val m = mapOf("quads" to recovering(14.0), "glutes" to recovering(20.0), "hamstrings" to recovering(5.0), "chest" to ready)
        assertEquals("Glutes and Quads still recovering: 20h", recoveringChip(listOf("quads", "glutes", "hamstrings"), m))
        assertEquals("Quads still recovering: 14h", recoveringChip(listOf("quads", "chest"), m))
        assertEquals("Hamstrings still recovering: 5h", recoveringChip(listOf("hamstrings"), m, max = 1))
    }

    @Test fun chipHiddenWhenReadyOrNever() {
        val m = mapOf("chest" to ready, "abs" to never)
        assertNull(recoveringChip(listOf("chest", "abs", "missing"), m))
        assertNull(recoveringChip(emptyList(), m))
    }

    @Test fun fractionIsClampedAndScaledByWindow() {
        assertEquals(0f, recoveryFraction("chest", ready), 0f)
        assertEquals(1f, recoveryFraction("chest", recovering(500.0)), 0f)
        assertEquals(0.5f, recoveryFraction("chest", recovering(30.0)), 1e-6f) // 48 * 1.25 = 60 h window
    }

    @Test fun recoveryCaptionCoversAllStates() {
        val m = mapOf("chest" to recovering(26.0 + 58 / 60.0), "quads" to recovering(40.0), "abs" to recovering(50.0), "calves" to ready, "lats" to never)
        assertEquals("3 muscles recovering, next ready Chest in 26h 58m", recoveryCaption(m))
        assertEquals("1 muscle recovering, next ready Quads in 40h 0m", recoveryCaption(mapOf("quads" to recovering(40.0), "abs" to never)))
        assertEquals("All muscles ready", recoveryCaption(mapOf("chest" to ready, "abs" to never)))
        assertEquals("Nothing logged yet", recoveryCaption(mapOf("chest" to never, "abs" to never)))
    }

    @Test fun listsForHome() {
        val m = mapOf("chest" to recovering(31.0 + 20 / 60.0), "quads" to ready, "abs" to never, "calves" to never)
        val lines = recoveringLines(m, utc, t0)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Chest: 31h 20m left, ready "))
        assertEquals(listOf("Quads"), readyNames(m))
        assertEquals(listOf("Abs", "Calves"), neverNames(m))
    }
}
