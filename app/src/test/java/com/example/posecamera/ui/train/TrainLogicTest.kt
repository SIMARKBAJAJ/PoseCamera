package com.example.posecamera.ui.train

import com.example.posecamera.data.LocalSet
import com.example.posecamera.lifts.Lifts
import com.example.posecamera.pose.RepSetSummary
import com.example.posecamera.pose.RepRules
import com.example.posecamera.sync.toLocalSet
import com.example.posecamera.ui.home.isPr
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun set(slug: String, kg: Double?, reps: Int, ts: Long) =
        LocalSet("c$ts", "other", slug, reps, null, null, null, kg, null, null, ts, ts, true)

    @Test fun kgStepsBy2_5AndClamps() {
        assertEquals(62.5, stepKg(60.0, 1), 0.0)
        assertEquals(57.5, stepKg(60.0, -1), 0.0)
        assertEquals(0.0, stepKg(1.0, -1), 0.0)
        assertEquals(MAX_KG, stepKg(MAX_KG, 1), 0.0)
    }

    @Test fun repsAndRpeClamp() {
        assertEquals(1, stepReps(1, -1)); assertEquals(MAX_REPS, stepReps(MAX_REPS, 1)); assertEquals(9, stepReps(8, 1))
        assertEquals(8.0, stepRpe(null, 1)!!, 0.0); assertNull(stepRpe(null, -1))
        assertNull(stepRpe(6.0, -1)); assertEquals(10.0, stepRpe(10.0, 1)!!, 0.0); assertEquals(8.5, stepRpe(8.0, 1)!!, 0.0)
    }

    @Test fun restTimerMaths() {
        assertEquals(1f, restFraction(90, 0), 0f)
        assertEquals(0.5f, restFraction(90, 45_000), 1e-6f)
        assertEquals(0f, restFraction(90, 200_000), 0f)
        assertEquals(0f, restFraction(0, 0), 0f)
        assertEquals(90, restSecondsLeft(90, 0)); assertEquals(45, restSecondsLeft(90, 45_000))
        assertEquals(46, restSecondsLeft(90, 44_100)); assertEquals(0, restSecondsLeft(90, 91_000))
    }

    @Test fun prNeedsAnEarlierSetAndABetterScore() {
        val h = listOf(set("bench-press", 60.0, 8, 1))
        assertFalse(isPr(emptyList(), "bench-press", 100.0, 5)) // baseline, not a PR
        assertTrue(isPr(h, "bench-press", 62.5, 8))
        assertTrue(isPr(h, "bench-press", 60.0, 9))
        assertFalse(isPr(h, "bench-press", 60.0, 8))
        assertFalse(isPr(h, "bench-press", 55.0, 8))
        assertFalse(isPr(h, "squat", 200.0, 8)) // other exercise
    }

    @Test fun platesText() {
        assertEquals("20 | 2x25 | 2x15", plateText(100.0))
        assertEquals("20 bar", plateText(20.0))
        assertEquals("20 | 2x20", plateText(60.0))
        assertEquals("20 | 2x2.5", plateText(25.0))
    }

    @Test fun previousAndTodayGrouping() {
        val day = 86_400_000L
        val now = 10 * day + 5_000
        val h = listOf(set("x", 50.0, 8, 8 * day + 10), set("x", 55.0, 8, 8 * day + 20), set("x", 40.0, 8, 6 * day), set("x", 60.0, 5, now - 100))
        assertEquals(listOf(50.0, 55.0), previousSets(h, "x", now, utc).map { it.loadKg })
        assertEquals(listOf(60.0), setsToday(h, "x", now, utc).map { it.loadKg })
        assertTrue(previousSets(h, "y", now, utc).isEmpty())
    }

    @Test fun manualSetKeepsFormFieldsNull() {
        val cfg = Lifts.benchPress
        val row = manualSummary(cfg, 8).toLocalSet("id", 1L)!!
        assertEquals(8, row.repCount); assertEquals(cfg.catalogSlug, row.exerciseSlug)
        assertNull(row.formscore); assertNull(row.cleanCount); assertFalse(row.synced)
    }

    @Test fun depthAndBands() {
        val r = RepRules(150f, 100f, 120f, 160f)
        assertEquals(0f, depthOf(170f, r), 0f); assertEquals(1f, depthOf(90f, r), 0f)
        assertEquals(0.5f, depthOf(130f, r), 1e-6f); assertEquals(0f, depthOf(null, r), 0f)
        assertEquals(FormBand.SAHI, formBand(90f)); assertEquals(FormBand.HALDI, formBand(70f)); assertEquals(FormBand.SINDOOR, formBand(10f))
    }
}
