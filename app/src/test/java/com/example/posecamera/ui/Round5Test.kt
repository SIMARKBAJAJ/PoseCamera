package com.example.posecamera.ui

import com.example.posecamera.ui.design.g3d.advancePhase
import com.example.posecamera.ui.library.CAMERA_ONLY
import com.example.posecamera.ui.library.loggableType
import com.example.posecamera.ui.library.parseMuscles
import com.example.posecamera.ui.nutrition.shortFoodName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Round5Test {
    @Test fun shortNameStripsUsdaSlug() {
        assertEquals("Bread chapati or roti whole wheat commercially prepared frozen",
            shortFoodName("Usda sr 174075 bread chapati or roti whole wheat commercially prepared frozen"))
    }

    @Test fun shortNameCutsAtCommaOrParen() {
        assertEquals("Bread", shortFoodName("Bread, chapati or roti, whole wheat"))
        assertEquals("Poha", shortFoodName("Poha (flattened rice, raw)"))
        assertEquals("Roti / chapati", shortFoodName("Roti / chapati"))
    }

    @Test fun musclesAreTolerant() {
        assertEquals(setOf("lats"), parseMuscles("Lats"))
        assertEquals(setOf("lats"), parseMuscles(" Latissimus Dorsi "))
        assertEquals(setOf("shoulders"), parseMuscles("Delts"))
        assertEquals(setOf("abs"), parseMuscles("abs"))
        assertEquals(setOf("quads", "calves", "traps"), parseMuscles("Quadriceps, Calf, Neck"))
        assertEquals(setOf("lats"), parseMuscles("middle back"))
    }

    @Test fun pushUpAndSquatAreCameraOnly() {
        assertEquals("PUSH_UP", loggableType("pushups"))
        assertEquals("SQUAT", loggableType("bodyweight-squat"))
        assertTrue(CAMERA_ONLY == setOf("SQUAT", "PUSH_UP"))
    }

    @Test fun phaseAdvancesAndWraps() {
        assertEquals(0.5f, advancePhase(0.25f, 250, 1000), 1e-5f)
        assertEquals(0.25f, advancePhase(0.75f, 500, 1000), 1e-5f)
        assertEquals(0.3f, advancePhase(0.3f, 0, 1000), 0f)
    }
}
