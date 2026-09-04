package com.example.posecamera.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateTransformTest {
    @Test
    fun mapsViewportCoordinatesDirectly() {
        val center = viewportPoint(0.5f, 0.5f, false, 1_080f, 1_920f)
        assertEquals(540f, center.x, 0.01f)
        assertEquals(960f, center.y, 0.01f)
    }

    @Test
    fun mirrorsFrontCameraCoordinatesOnce() {
        val point = viewportPoint(0.8f, 0.5f, true, 1_080f, 1_920f)
        assertEquals(216f, point.x, 0.01f)
        assertEquals(960f, point.y, 0.01f)
    }

    @Test
    fun invalidViewportMapsToOrigin() {
        val point = viewportPoint(0.5f, 0.5f, false, 0f, 1_920f)
        assertEquals(0f, point.x, 0.01f)
        assertEquals(0f, point.y, 0.01f)
    }
}
