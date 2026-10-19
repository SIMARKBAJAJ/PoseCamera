package com.example.posecamera.ui.progress

import com.example.posecamera.data.LocalSet
import com.example.posecamera.data.LocalWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class ProgressLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val now = 100 * day + 12 * 3_600_000L // 1970-04-11 noon

    private fun w(date: String, kg: Double = 70.0) = LocalWeight("w$date", kg, date, 0, true)
    private fun set(slug: String, kg: Double?, reps: Int, ts: Long) =
        LocalSet("s$ts$slug", "other", slug, reps, null, null, null, kg, null, null, ts, ts, true)

    @Test fun windowKeepsLastNDaysOldestFirst() {
        val all = listOf(w("1970-04-11"), w("1970-03-01"), w("1970-04-05"), w("1970-04-12"))
        // 7 days ending 1970-04-11: from 04-05
        assertEquals(listOf("1970-04-05", "1970-04-11", "1970-04-12"), weightWindow(all, now, utc, 7).map { it.date })
        assertEquals(4, weightWindow(all, now, utc, 90).size)
    }

    @Test fun prBestsOnePerExerciseWithEpley() {
        val ss = listOf(
            set("bench", 60.0, 10, 1 * day), set("bench", 80.0, 5, 2 * day), set("bench", 100.0, 1, 3 * day),
            set("push-up", null, 20, 5 * day), set("push-up", null, 30, 4 * day),
        )
        val p = prBests(ss)
        assertEquals(listOf("push-up", "bench"), p.map { it.key }) // newest best first
        val bench = p[1]
        assertEquals(100.0, bench.loadKg!!, 0.0) // Epley: 100x1 = 103.3 beats 80x5 = 93.3 and 60x10 = 80
        assertEquals(100.0 * (1 + 1 / 30.0), bench.e1rm!!, 1e-9)
    }

    @Test fun bodyweightBestHasNoE1rmAndRepsRank() {
        val p = prBests(listOf(set("push-up", null, 20, day), set("push-up", null, 30, 2 * day))).single()
        assertNull(p.e1rm); assertEquals(30, p.reps)
    }

    @Test fun weeklyVolumeBucketsRollingWeeks() {
        val ss = listOf(set("a", 100.0, 5, now - day), set("a", 50.0, 10, now - 8 * day), set("a", null, 20, now - day), set("a", 10.0, 10, now - 100 * day))
        val v = weeklyVolume(ss, now, 3)
        assertEquals(listOf(0.0, 500.0, 500.0), v)
    }

    @Test fun weightStepperClampsAndRounds() {
        assertEquals(70.1, stepWeight(70.0, 1), 1e-9); assertEquals(20.0, stepWeight(20.0, -1), 0.0)
    }
}

class EpochDayTest {
    @Test fun epochDayCountsCalendarDays() {
        org.junit.Assert.assertEquals(0L, epochDay("1970-01-01"))
        org.junit.Assert.assertEquals(100L, epochDay("1970-04-11"))
        org.junit.Assert.assertEquals(1L, epochDay("2024-03-01") - epochDay("2024-02-29"))
    }
}
