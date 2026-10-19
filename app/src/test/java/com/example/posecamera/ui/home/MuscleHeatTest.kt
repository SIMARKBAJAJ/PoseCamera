package com.example.posecamera.ui.home

import com.example.posecamera.data.LocalSet
import com.example.posecamera.lifts.Lifts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuscleHeatTest {
    private val day = 86_400_000L
    private val now = 100 * day

    private fun set(t: Long, type: String = "other", slug: String? = null, reps: Int = 5, form: Float? = null) =
        LocalSet("id$t$type$slug", type, slug, reps, null, null, form, null, null, null, t, t, true)

    @Test fun everyLiftHasAMappingOntoKnownMuscleKeys() {
        assertEquals(22, Lifts.all.size)
        for (l in Lifts.all) {
            val m = musclesOf(l.catalogSlug)
            assertTrue("no mapping for ${l.catalogSlug}", !m.isNullOrEmpty())
            assertTrue(MUSCLE_KEYS.containsAll(m!!.keys))
        }
        assertTrue(musclesOf("squat")!!.isNotEmpty() && musclesOf("push-up")!!.isNotEmpty())
    }

    @Test fun heatNormalisedBySetVolume() {
        val h = muscleHeat(listOf(set(now, slug = "crunches"), set(now, slug = "crunches"), set(now - 1, slug = "plank")), now)
        assertEquals(1f, h["abs"]!!, 0f)
        assertEquals(0.5f / 3f, h["lower_back"]!!, 1e-6f)
    }

    @Test fun unknownOldAndZeroRepSetsContributeNothing() {
        assertTrue(muscleHeat(listOf(set(now, "other", "mystery-move"), set(now, "other", null)), now).isEmpty())
        assertTrue(muscleHeat(listOf(set(now - 8 * day, slug = "crunches"), set(now, slug = "crunches", reps = 0)), now).isEmpty())
    }

    @Test fun captionNamesHotAndUntouched() {
        val h = muscleHeat(listOf(set(now, slug = "barbell-bench-press-medium-grip")), now)
        val c = heatCaption(h)!!
        assertTrue(c, c.startsWith("Chest") && c.contains(" garam, "))
        assertTrue(c, c.contains("back") && c.endsWith("thanda"))
        assertNull(heatCaption(emptyMap()))
    }

    @Test fun validSessionAndPlural() {
        val s = listOf(set(5, reps = 0), set(1, reps = 3))
        assertEquals(1, validSets(s).size)
        assertNull(lastSession(listOf(set(5, reps = 0))))
        assertEquals(1, lastSession(s)!!.sets)
        assertEquals("1 set", plural(1, "set"))
        assertEquals("2 sets", plural(2, "set"))
        assertEquals("0 reps", plural(0, "rep"))
    }

    @Test fun formBands() {
        assertEquals(FormBand.NONE, formBandOf(null))
        assertEquals(FormBand.GOOD, formBandOf(80f))
        assertEquals(FormBand.OK, formBandOf(79.9f))
        assertEquals(FormBand.OK, formBandOf(60f))
        assertEquals(FormBand.LOW, formBandOf(59f))
    }
}
