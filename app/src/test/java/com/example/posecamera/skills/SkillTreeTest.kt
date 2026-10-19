package com.example.posecamera.skills

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SkillTreeTest {
    private fun catalogSlugs(): Set<String> {
        val f = listOf("../data/out/exercises.json", "data/out/exercises.json").map(::File).firstOrNull { it.isFile }
        assumeTrue("exercises.json not found from ${File(".").absoluteFile}", f != null)
        return JsonParser.parseString(f!!.readText()).asJsonObject.getAsJsonArray("exercises")
            .map { it.asJsonObject.get("slug").asString }.toSet()
    }

    @Test fun thirtyTwoStepsWithUniqueIds() {
        assertEquals(32, SKILL_STEPS.size)
        assertEquals(SKILL_STEPS.size, SKILL_STEPS.map { it.id }.toSet().size)
        assertEquals(12, SKILL_STEPS.count { !it.inCatalog })
    }

    @Test fun requiresExistAndComeEarlier() {
        val seen = HashSet<String>()
        for (s in SKILL_STEPS) {
            for (r in s.requires) assertTrue("${s.id} requires $r which is not earlier", r in seen)
            seen += s.id
        }
    }

    @Test fun inCatalogIdsExistInExercisesJson() {
        val cat = catalogSlugs()
        for (s in SKILL_STEPS.filter { it.inCatalog }) assertTrue("${s.id} missing from catalog", s.id in cat)
    }

    @Test fun notInCatalogIdsAreAbsentFromExercisesJson() {
        val cat = catalogSlugs()
        for (s in SKILL_STEPS.filter { !it.inCatalog }) assertFalse("${s.id} is in the catalog", s.id in cat)
    }

    @Test fun poseTrackableStepsAreExactlyTheFour() {
        assertEquals(setOf("pushups", "bodyweight-squat", "pullups", "plank"), SKILL_STEPS.filter { it.poseTrackable }.map { it.id }.toSet())
    }
}
