package com.example.posecamera.ui.home

import com.example.posecamera.data.LocalSet
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val now = 100 * day + 3_600_000L

    private fun set(t: Long, type: String = "squat", slug: String? = null, reps: Int = 5, load: Double? = null, form: Float? = null) =
        LocalSet("id$t$type", type, slug, reps, null, null, form, load, null, null, t, t, true)

    @Test fun chainMarksDaysAndBreaksOnGap() {
        val c = chainOf(listOf(now, now - day, now - 3 * day), now, utc)
        assertEquals(14, c.size)
        assertEquals(listOf(false, true, false, true, true), c.takeLast(5))
        assertEquals(3, c.count { it })
    }

    @Test fun chainEmpty() = assertTrue(chainOf(emptyList(), now, utc).none { it })

    @Test fun firstSetIsNotAPr() = assertTrue(prsOf(listOf(set(1))).isEmpty())

    @Test fun prsOnlyOnImprovementNewestFirstOnePerExercise() {
        val sets = listOf(
            set(1_000, reps = 5), set(2_000, reps = 8), set(3_000, reps = 7), set(4_000, reps = 9),
            set(1_500, "other", "bench-press", 5, 60.0), set(2_500, "other", "bench-press", 5, 70.0),
            set(1_200, "push-up", reps = 10), set(5_000, "push-up", reps = 20), set(3_500, "push-up", reps = 15),
        )
        val prs = prsOf(sets)
        assertEquals(listOf("push-up", "squat", "bench-press"), prs.map { it.key })
        assertEquals(5_000L, prs[0].timestamp)
        assertEquals(4_000L, prs[1].timestamp)
        assertEquals(70.0, prs[2].loadKg!!, 0.0)
        assertEquals(2, prsOf(sets, limit = 2).size)
    }

    @Test fun lastSessionChainsByGapAndAveragesJudged() {
        val m = 60_000L
        val s = lastSession(
            listOf(set(0, form = 100f), set(200 * m, form = 60f), set(250 * m, form = 80f), set(251 * m, form = null)),
        )!!
        assertEquals(3, s.sets)
        assertEquals(15, s.reps)
        assertEquals(70f, s.avgForm!!, 0.001f)
        assertEquals(200 * m, s.startMs)
        assertNull(lastSession(emptyList()))
        assertNull(lastSession(listOf(set(1)))!!.avgForm)
    }
}
