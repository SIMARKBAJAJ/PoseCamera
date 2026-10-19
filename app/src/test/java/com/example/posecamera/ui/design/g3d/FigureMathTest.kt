package com.example.posecamera.ui.design.g3d

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FigureMathTest {
    @Test
    fun sortsFarToNearAndStable() {
        val d = floatArrayOf(2.9f, 3.4f, 3.1f, 3.4f, 2.5f)
        val o = IntArray(5)
        sortFarToNear(d, o, 5)
        assertArrayEquals(intArrayOf(1, 3, 2, 0, 4), o) // equal depths keep index order
    }

    @Test
    fun sortHandlesSmallAndSortedInput() {
        val o = IntArray(16)
        sortFarToNear(FloatArray(16) { 16f - it }, o, 16) // already far-first
        assertArrayEquals(IntArray(16) { it }, o)
        sortFarToNear(FloatArray(16) { it.toFloat() }, o, 16) // reversed
        assertArrayEquals(IntArray(16) { 15 - it }, o)
        sortFarToNear(floatArrayOf(1f), o, 1); assertEquals(0, o[0])
    }

    @Test
    fun coincidentBonesAreDetectedWithinTolerance() {
        val x = floatArrayOf(0f, 1f, 2f); val y = FloatArray(3); val z = FloatArray(3)
        val gx = floatArrayOf(0.01f, 1.01f, 2.5f)
        assertTrue(boneCoincides(x, y, z, gx, y, z, 0, 1, 0.02f))
        assertFalse(boneCoincides(x, y, z, gx, y, z, 1, 2, 0.02f)) // one end moved
        assertFalse(boneCoincides(x, y, z, gx, y, z, 0, 1, 0.005f)) // tighter tolerance
        val gz = floatArrayOf(0f, 0.03f, 0f) // depth counts too
        assertFalse(boneCoincides(x, y, z, x, y, gz, 0, 1, 0.02f))
    }

    @Test
    fun depthShadingStaysInSpec() {
        assertEquals(1f, depthAlpha(2.0f, 3f), 1e-5f)
        assertEquals(FAR_ALPHA_MIN, depthAlpha(4.0f, 3f), 1e-5f)
        assertTrue(depthAlpha(2.8f, 3f) > depthAlpha(3.2f, 3f))
        for (d in listOf(1f, 2f, 3f, 4f, 9f)) {
            val w = depthWidthDp(d, 3f)
            assertTrue(w in MIN_W_DP..MAX_W_DP)
        }
        assertTrue(depthWidthDp(2.6f, 3f) > depthWidthDp(3.4f, 3f))
    }
}
