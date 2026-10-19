package com.example.posecamera.ui.design

import com.example.posecamera.ui.design.g3d.Cam3
import com.example.posecamera.ui.design.g3d.MIN_DEPTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Cam3DepthTest {
    @Test fun pointAtOrBehindEyeStaysFinite() {
        val cam = Cam3(0f, 0f, 3f)
        for (z in listOf(-3f, -5f)) { // side view: -z is toward the eye (depth 0 and negative)
            val p = cam.project(1f, 1f, z)
            assertTrue(p.sx.isFinite() && p.sy.isFinite())
            assertEquals(MIN_DEPTH, p.depth, 0f)
        }
    }

    @Test fun orthoHasNoPerspectiveScaling() {
        val near = Cam3(0f, 0f, 3f, ortho = true).project(1f, 1f, -1f)
        val far = Cam3(0f, 0f, 3f, ortho = true).project(1f, 1f, 1f)
        assertEquals(near.sx, far.sx, 1e-6f)
        assertEquals(near.sy, far.sy, 1e-6f)
    }
}
