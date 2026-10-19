package com.example.posecamera.ui.design

import com.example.posecamera.power.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTierTest {
    @Test
    fun rawTierDrivesMotionLevel() {
        assertEquals(MotionLevel.FULL, motionLevelOfTier(Tier.NORMAL, 1f))
        assertEquals(MotionLevel.REDUCED, motionLevelOfTier(Tier.SAVER, 1f))
        assertEquals(MotionLevel.OFF, motionLevelOfTier(Tier.CRITICAL, 1f))
    }

    @Test
    fun systemAnimationsOffForcesOffInEveryTier() {
        for (t in Tier.entries) assertEquals(MotionLevel.OFF, motionLevelOfTier(t, 0f))
        assertEquals(MotionLevel.FULL, motionLevelOfTier(Tier.NORMAL, 0.5f)) // slowed, not removed
    }
}
