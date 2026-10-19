package com.example.posecamera.ui.design

import com.example.posecamera.ui.design.g3d.Cam3
import com.example.posecamera.ui.design.g3d.plateBreakdown
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTest {
    @Test fun platesGreedyPerSide() {
        assertEquals(listOf(25f, 15f), plateBreakdown(100f))
        assertEquals(listOf(20f), plateBreakdown(60f))
        assertEquals(listOf(25f, 15f, 1.25f), plateBreakdown(102.5f))
        assertEquals(listOf(15f), plateBreakdown(70f, 40f))
    }

    @Test fun barOnlyOrLessHasNoPlates() {
        assertTrue(plateBreakdown(20f).isEmpty())
        assertTrue(plateBreakdown(10f).isEmpty())
    }

    @Test fun originProjectsToCentre() {
        val p = Cam3(0.7f, 0.3f, 3f).project(0f, 0f, 0f)
        assertEquals(0f, p.sx, 1e-6f); assertEquals(0f, p.sy, 1e-6f); assertEquals(3f, p.depth, 1e-6f)
    }

    @Test fun fartherIsSmaller() {
        val cam = Cam3(0f, 0f, 3f) // side view: +z is away from the eye
        val near = cam.project(1f, 0f, 0f); val far = cam.project(1f, 0f, 1f)
        assertTrue(far.depth > near.depth)
        assertTrue(abs(far.sx) < abs(near.sx))
    }

    @Test fun frontViewShowsZAcrossScreen() {
        val p = Cam3((Math.PI / 2).toFloat(), 0f, 3f).project(0f, 0f, 0.5f)
        assertEquals(0.5f, abs(p.sx), 1e-4f) // depth stays 3, so no scaling
    }

    @Test fun motionLevelMapping() {
        assertEquals(MotionLevel.FULL, motionLevelOf(1f, 1f))
        assertEquals(MotionLevel.REDUCED, motionLevelOf(0.5f, 1f))
        assertEquals(MotionLevel.OFF, motionLevelOf(0f, 1f))
        assertEquals(MotionLevel.OFF, motionLevelOf(1f, 0f))
    }
}
