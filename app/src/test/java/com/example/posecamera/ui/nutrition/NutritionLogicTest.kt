package com.example.posecamera.ui.nutrition

import com.example.posecamera.data.LocalFoodLog
import com.example.posecamera.data.LocalProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLogicTest {
    private fun log(meal: String, kcal: Double?, computed: String? = null) =
        LocalFoodLog("id$kcal$meal", meal, "2026-01-01", null, null, null, "x", kcal, 10.0.takeIf { kcal != null }, 20.0.takeIf { kcal != null }, 5.0.takeIf { kcal != null }, computed, 0, false)

    private fun profile(sex: String? = "male", year: Int? = 1996, h: Double? = 180.0, w: Double? = 80.0, goal: String? = "maintain", act: String? = "moderate") =
        LocalProfile("profile", sex, year, h, w, act, goal, null, 0, false)

    @Test fun bmrMatchesMifflinForBothSexes() {
        assertEquals(1780.0, bmrMifflin("male", 80.0, 180.0, 30), 1e-9) // 800 + 1125 - 150 + 5
        assertEquals(1320.25,bmrMifflin("female", 60.0, 165.0, 30), 1e-9) // 600 + 1031.25 - 150 - 161 = 1320.25
    }

    @Test fun targetsFollowGoalAndFlagDefaultsOnlyWhenIncomplete() {
        val maintain = targetsOf(profile(), 2026) // age 30
        assertFalse(maintain.isDefault)
        assertEquals((1780.0 * 1.55).let { Math.round(it).toDouble() }, maintain.kcal, 0.0)
        assertEquals(128.0, maintain.proteinG, 1e-9) // 1.6 * 80
        assertTrue(targetsOf(profile(goal = "lose"), 2026).kcal < maintain.kcal)
        assertTrue(targetsOf(profile(goal = "gain"), 2026).kcal > maintain.kcal)
        val d = targetsOf(profile(h = null), 2026)
        assertTrue(d.isDefault); assertEquals(2000.0, d.kcal, 0.0); assertEquals(120.0, d.proteinG, 0.0)
        assertTrue(targetsOf(null, 2026).isDefault)
    }

    @Test fun loseGoalIsFlooredBySex() {
        val tiny = profile(sex = "female", year = 1950, h = 140.0, w = 35.0, goal = "lose", act = "sedentary")
        assertEquals(1200.0, targetsOf(tiny, 2026).kcal, 0.0)
    }

    @Test fun totalsUseOwnMacrosThenComputedThenZero() {
        val computed = """{"kcal": 100.5, "protein_g": 1.0, "carbs_g": 2.0, "fat_g": 3.0}"""
        val t = totalsOf(listOf(log("lunch", 200.0), log("lunch", null, computed), log("snack", null, null)))
        assertEquals(300.5, t.kcal, 1e-9); assertEquals(11.0, t.proteinG, 1e-9)
        assertEquals(22.0, t.carbsG, 1e-9); assertEquals(8.0, t.fatG, 1e-9)
        assertEquals(Macros(), macrosOf(log("snack", null, "not json")))
    }

    @Test fun mealsAreGroupedInOrderWithEmptyOnes() {
        val m = byMeal(listOf(log("dinner", 1.0), log("breakfast", 2.0)))
        assertEquals(MEAL_ORDER, m.keys.toList())
        assertEquals(1, m.getValue("dinner").size); assertTrue(m.getValue("lunch").isEmpty())
    }

    @Test fun scalingAndSteppingAndDayShift() {
        assertEquals(150.0, scaled(Macros(kcal = 100.0), 150.0).kcal, 1e-9)
        assertEquals(10.0, stepGrams(10.0, -1), 0.0); assertEquals(5000.0, stepGrams(5000.0, 1), 0.0)
        assertEquals("2026-03-01", shiftDay("2026-02-28", 1)); assertEquals("2025-12-31", shiftDay("2026-01-01", -1))
    }
}
