package com.example.posecamera.ui.home

import com.example.posecamera.data.LocalSet
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    private val h = 3_600_000L
    private val utc = TimeZone.getTimeZone("UTC")
    private val t0 = 1_000 * 24 * h

    private var n = 0
    private fun set(t: Long, slug: String?, type: String = "other", reps: Int = 5, rpe: Double? = null, failed: Boolean = false) =
        LocalSet("id${n++}", type, slug, reps, null, null, null, null, rpe, null, t, t, true, failed)

    /** [count] crunch sets (abs only, SMALL 24h) spaced 10 min apart, the last at [end]. */
    private fun crunches(end: Long, count: Int, rpe: Double? = null) =
        (0 until count).map { set(end - (count - 1 - it) * 600_000L, "crunches", rpe = rpe) }

    private fun absReady(sets: List<LocalSet>, speed: RecoverySpeed = RecoverySpeed.NORMAL) =
        recoveryFor(sets, t0, speed, utc).getValue("abs").readyAtMs!!

    private fun utcMs(y: Int, mo: Int, d: Int, hh: Int, mm: Int) =
        GregorianCalendar(utc).apply { clear(); set(y, mo - 1, d, hh, mm) }.timeInMillis

    @Test fun everyKeyHasASize() {
        assertEquals(MUSCLE_KEYS, MUSCLE_SIZE.keys)
    }

    @Test fun neverWhenNoSets() {
        val r = recoveryFor(emptyList(), t0, RecoverySpeed.NORMAL, utc)
        assertEquals(MUSCLE_KEYS, r.keys)
        assertTrue(r.values.all { it.state == RecoveryState.NEVER && it.readyAtMs == null && it.hoursLeft == null })
    }

    @Test fun secondaryCountsHalf() {
        val r = recoveryFor(List(3) { set(t0, "barbell-bench-press-medium-grip") }, t0, RecoverySpeed.NORMAL, utc)
        assertEquals(RecoveryState.RECOVERING, r.getValue("chest").state)
        assertEquals(t0 + 48 * h, r.getValue("chest").readyAtMs)      // 3.0 effective -> 1.0
        assertEquals(t0 + 27 * h, r.getValue("triceps").readyAtMs)    // 1.5 effective -> 0.75 * 36
        assertEquals(t0 + 27 * h, r.getValue("shoulders").readyAtMs)
        assertEquals(RecoveryState.NEVER, r.getValue("quads").state)
    }

    @Test fun onlyLatestSessionCounts() {
        // 12 sets two days earlier would be 1.25, but the latest session is a single set -> 0.75
        val old = crunches(t0 - 48 * h, 12)
        assertEquals(t0 + 18 * h, absReady(old + crunches(t0, 1)))
    }

    @Test fun gapOver3hSplitsSessions() {
        val a = crunches(t0 - 4 * h, 2)          // 2 sets, 4h before
        val b = crunches(t0, 2)
        assertEquals(t0 + 18 * h, absReady(a + b))   // split: latest has 2 -> 0.75
        val c = crunches(t0 - 3 * h, 2)          // exactly 3h before the first of b? b starts t0-10min -> gap 2h50m
        assertEquals(t0 + 24 * h, absReady(c + b))   // merged: 4 sets -> 1.0
    }

    @Test fun volumeScaleBoundaries() {
        assertEquals(t0 + 18 * h, absReady(crunches(t0, 2) + listOf(set(t0 - 3_000_000L, "crunches", reps = 0)))) // 2 valid
        assertEquals(t0 + 24 * h, absReady(crunches(t0, 3)))
        assertEquals(t0 + 30 * h, absReady(crunches(t0, 10)))
        assertEquals(t0 + 30 * h, absReady(crunches(t0, 3, rpe = 9.0)))
        assertEquals(t0 + 24 * h, absReady(crunches(t0, 3, rpe = 8.5)))
        // 2.5 effective (plank: abs 1, 5 sets of bench would not). 1 crunch + 3 plank-assist sets cannot reach abs; use goblet-squat abs 0.5
        val mixed = listOf(set(t0, "crunches"), set(t0, "crunches"), set(t0, "goblet-squat"))
        assertEquals(t0 + 18 * h, absReady(mixed))
    }

    @Test fun rpeOnSecondarySetDoesNotCountAsPrimary() {
        // squat sets: hamstrings is secondary; rpe 9 must not bump hamstrings (1.5 eff -> 0.75 * 48 = 36h)
        val r = recoveryFor(List(3) { set(t0, "squat", rpe = 9.0) }, t0, RecoverySpeed.NORMAL, utc)
        assertEquals(t0 + 60 * h, r.getValue("quads").readyAtMs)      // primary rpe 9 -> 1.25 * 48
        assertEquals(t0 + 36 * h, r.getValue("hamstrings").readyAtMs)
    }

    @Test fun speedMultipliers() {
        val s = crunches(t0, 3)
        assertEquals(t0 + 30 * h, absReady(s, RecoverySpeed.SLOWER))
        assertEquals(t0 + 24 * h, absReady(s, RecoverySpeed.NORMAL))
        assertEquals(t0 + Math.round(19.2 * h), absReady(s, RecoverySpeed.FASTER))
    }

    @Test fun clampTo12And120() {
        // lowest reachable: 24 * 0.75 * 0.8 = 14.4 so clamp isn't hit for abs; check the bounds via the highest: 48*1.25*1.25 = 75 (<120)
        val r = recoveryFor(List(10) { set(t0, "squat") }, t0, RecoverySpeed.SLOWER, utc)
        assertEquals(t0 + 75 * h, r.getValue("quads").readyAtMs)
        val low = recoveryFor(listOf(set(t0, "crunches")), t0, RecoverySpeed.FASTER, utc).getValue("abs").readyAtMs!!
        assertTrue(low - t0 >= 12 * h && low - t0 <= 120 * h)
        assertEquals(t0 + Math.round(14.4 * h), low)
    }

    @Test fun invalidSetsIgnored() {
        val r = recoveryFor(
            listOf(set(t0, "crunches", reps = 0), set(t0, "crunches", failed = true), set(t0, null, type = "other")),
            t0, RecoverySpeed.NORMAL, utc,
        )
        assertTrue(r.values.all { it.state == RecoveryState.NEVER })
    }

    @Test fun cameraTypesResolveWithoutSlug() {
        val r = recoveryFor(listOf(set(t0, null, type = "push-up")), t0, RecoverySpeed.NORMAL, utc)
        assertEquals(RecoveryState.RECOVERING, r.getValue("chest").state)
    }

    @Test fun readyAfterWindowAndHoursLeft() {
        val s = crunches(t0, 3)
        val ready = recoveryFor(s, t0 + 24 * h, RecoverySpeed.NORMAL, utc).getValue("abs")
        assertEquals(RecoveryState.READY, ready.state)
        assertNull(ready.hoursLeft)
        val rec = recoveryFor(s, t0 + 6 * h, RecoverySpeed.NORMAL, utc).getValue("abs")
        assertEquals(18.0, rec.hoursLeft!!, 1e-9)
    }

    @Test fun listsSortedBySoonestReady() {
        val sets = listOf(set(t0, "crunches"), set(t0 - 10 * h, "standing-calf-raises"))
        val rec = recoveringList(recoveryFor(sets, t0, RecoverySpeed.NORMAL, utc))
        assertEquals(listOf("calves", "abs"), rec.map { it.first }) // calves ready t0+8h, abs t0+18h
        val later = recoveryFor(sets, t0 + 200 * h, RecoverySpeed.NORMAL, utc)
        assertEquals(listOf("calves", "abs"), readyList(later).map { it.first })
        assertTrue(recoveringList(later).isEmpty())
    }

    @Test fun formatLeftRoundsAndNeverNegative() {
        assertEquals("31h 20m", formatLeft(31.0 + 20.0 / 60))
        assertEquals("0h 0m", formatLeft(-5.0))
        assertEquals("0h 0m", formatLeft(0.0))
        assertEquals("1h 0m", formatLeft(59.7 / 60)) // 59.7 min rounds up to 60
        assertEquals("0h 59m", formatLeft(59.4 / 60))
    }

    @Test fun formatReadyTimezones() {
        val ms = utcMs(2025, 6, 3, 13, 10) // Tue 13:10 UTC = 18:40 IST
        assertEquals("Tue 1:10 pm", formatReady(ms, utc, 0))
        assertEquals("Tue 6:40 pm", formatReady(ms, TimeZone.getTimeZone("Asia/Kolkata"), 0))
        assertEquals("Tue 12:05 am", formatReady(utcMs(2025, 6, 3, 0, 5), utc, 0))
    }

    @Test fun dstSpringForwardUsesAbsoluteTimeThenWallClock() {
        val ny = TimeZone.getTimeZone("America/New_York")
        // Sat 2025-03-08 23:00 EST = 04:00Z Sun; DST starts 02:00 EST that Sunday.
        val last = utcMs(2025, 3, 9, 4, 0)
        val sets = listOf(set(last - 1_200_000, "standing-calf-raises"), set(last - 600_000, "standing-calf-raises"), set(last, "standing-calf-raises"))
        val r = recoveryFor(sets, last, RecoverySpeed.NORMAL, ny).getValue("calves")
        assertEquals(last + 24 * h, r.readyAtMs)                 // absolute 24h
        assertEquals("Mon 12:00 am", formatReady(r.readyAtMs!!, ny, last)) // wall clock after losing an hour
        assertEquals("Sun 11:00 pm", formatReady(r.readyAtMs!! - h, ny, last))
    }

    @Test fun catalogNamesMapToKeys() {
        val names = listOf(
            "abdominals", "abductors", "adductors", "back", "biceps", "calves", "chest",
            "forearms", "glutes", "hamstrings", "neck", "quadriceps", "shoulders", "triceps",
        )
        for (n in names) {
            val m = catalogMuscles(n)
            assertNotNull(n, m)
            assertTrue(n, MUSCLE_KEYS.containsAll(m!!.keys))
        }
        assertEquals(setOf("lats", "lower_back", "traps"), catalogMuscles("back")!!.keys)
        assertTrue(catalogMuscles("back")!!.values.all { it == 0.5f })
        assertEquals(mapOf("abs" to 1f), catalogMuscles("abdominals"))
        assertEquals(mapOf("glutes" to 1f), catalogMuscles("abductors"))
        assertEquals(mapOf("quads" to 1f), catalogMuscles("adductors"))
        assertEquals(mapOf("traps" to 1f), catalogMuscles("neck"))
        assertEquals(mapOf("quads" to 1f), catalogMuscles("quadriceps"))
        assertNull(catalogMuscles("wings"))
    }
}
