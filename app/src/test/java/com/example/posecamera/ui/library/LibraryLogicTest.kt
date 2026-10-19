package com.example.posecamera.ui.library

import com.example.posecamera.demo.DemoLift
import com.example.posecamera.lifts.Lifts
import com.example.posecamera.ui.design.g3d.MuscleSide
import com.example.posecamera.ui.home.MUSCLE_KEYS
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {
    @Test fun searchKeyNormalises() {
        assertEquals("bench press", searchKey("  Bench%_  PRESS\\ "))
        assertEquals("", searchKey("   "))
    }

    @Test fun everyLiftMapsToADemo() {
        for (l in Lifts.all) assertNotNull(l.catalogSlug, demoOf(l.catalogSlug))
        assertEquals(DemoLift.SEATED_ROW, demoOf("seated-cable-rows"))
        assertEquals(DemoLift.SQUAT, demoOf("squat"))
        assertNull(demoOf("mystery-move"))
    }

    @Test fun targetPhaseLandsOnKey() {
        assertEquals(0.5f, targetPhase(DemoLift.SQUAT), 0f)
        assertEquals(0.5f, targetPhase(DemoLift.STEP_UP), 0f)
    }

    @Test fun muscleFilterAndParse() {
        assertTrue(FILTER_MUSCLES.all { it in MUSCLE_KEYS && muscleLike(it).isNotEmpty() })
        assertEquals("", muscleLike(null))
        assertTrue(muscleLike("lower_back").none { it == '%' || it == '_' })
        assertEquals(setOf("abs", "quads"), parseMuscles("Abdominals,Quadriceps"))
        assertEquals(setOf("lower_back"), parseMuscles("lower back"))
        assertEquals(mapOf("abs" to 1f), heatOf("unknown", "abdominals"))
        assertEquals(1f, heatOf("crunches", "")["abs"]!!, 0f)
    }

    @Test fun backChipsMapToCatalogBack() {
        assertEquals("back", muscleLike("lats"))
        assertEquals("back", muscleLike("traps")); assertEquals("back", muscleLike("lower_back"))
        assertEquals(listOf("shrug", "upright", "face pull"), nameLikes("traps"))
        assertEquals(listOf("deadlift", "extension", "good morning"), nameLikes("lower_back"))
        assertTrue(nameLikes("lats").isEmpty() && nameLikes("chest").isEmpty() && nameLikes(null).isEmpty())
        assertTrue(listOf("traps", "lower_back").all { k -> nameLikes(k).all { f -> f == f.lowercase() && f.none { it in "%_\\" } } })
        val p = parseMuscles("Back")
        assertTrue("lats" in p && "lower_back" !in p)
    }

    // Applies the DAO's muscle predicate to the real catalog (expected at time of writing: Back 114, Traps 14, Lower back 11).
    @Test fun chipsReturnCatalogRows() {
        val f = listOf("../data/out/exercises.json", "data/out/exercises.json").map { java.io.File(it) }.firstOrNull { it.exists() }
        org.junit.Assume.assumeTrue(f != null)
        val rows = com.google.gson.JsonParser.parseString(f!!.readText()).asJsonObject.getAsJsonArray("exercises").map { it.asJsonObject }
        fun count(key: String): Int {
            val like = muscleLike(key); val names = nameLikes(key)
            return rows.count { e ->
                val prim = e.getAsJsonArray("primary_muscles").joinToString(",") { it.asString }.lowercase()
                val name = e.get("name").asString.lowercase()
                like in prim && (names.isEmpty() || names.any { it in name })
            }
        }
        val counts = FILTER_MUSCLES.associateWith { count(it) }
        assertTrue("Back $counts", counts.getValue("lats") >= 100)
        assertTrue("Traps $counts", counts.getValue("traps") >= 5)
        assertTrue("Lower back $counts", counts.getValue("lower_back") >= 5)
        for (k in listOf("chest", "shoulders", "biceps", "triceps", "forearms", "abs", "quads", "calves", "glutes", "hamstrings"))
            assertTrue("$k $counts", counts.getValue(k) > 0)
    }

    @Test fun sideAndPrimarySecondary() {
        assertEquals(MuscleSide.FRONT, sideFor(mapOf("chest" to 1f, "triceps" to 0.5f)))
        assertEquals(MuscleSide.BACK, sideFor(mapOf("lats" to 1f, "biceps" to 0.5f)))
        val h = mapOf("lats" to 1f, "biceps" to 0.5f)
        assertEquals(listOf("lats"), primaryOf(h)); assertEquals(listOf("biceps"), secondaryOf(h))
    }

    @Test fun barbellAndLogging() {
        assertTrue(isBarbell("barbell-deadlift", null)); assertTrue(isBarbell("standing-military-press", null))
        assertTrue(isBarbell("x", "Barbell")); assertFalse(isBarbell("plank", "Body only"))
        assertEquals("BENCH_PRESS", loggableType("barbell-bench-press-medium-grip"))
        assertNull(loggableType("plank")); assertNull(loggableType("nope"))
    }

    @Test fun cues() {
        val c = cuesFor("dumbbell-lunges")
        assertTrue(c.how.isNotEmpty() && c.watch.isNotEmpty())
        assertTrue(cuesFor("nope").how.isEmpty())
    }

    @Test fun nearestYawTakesShortWay() {
        val tau = (2 * PI).toFloat()
        assertEquals(tau + 0.1f, nearestYaw(tau - 0.1f, 0.1f + 0f) + 0f, 1e-4f) // 6.18 -> 0.1 is +0.2 forward
        assertEquals(0.2f, nearestYaw(0.1f, 0.2f), 1e-5f)
        assertEquals(-tau + 0.1f, nearestYaw(-tau, 0.1f), 1e-4f)
    }

    @Test fun loadMore() {
        assertTrue(shouldLoadMore(26, 30, 30)); assertFalse(shouldLoadMore(10, 30, 30))
        assertFalse(shouldLoadMore(29, 12, 30)); assertFalse(shouldLoadMore(0, 0, 30))
    }
}
