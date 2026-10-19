package com.example.posecamera.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyTest {
    private fun state(pct: Int = 80, charging: Boolean = false, saver: Boolean = false, thermal: Int = 0) =
        PowerState(pct, charging, saver, thermal)

    @Test
    fun tierBoundaries() {
        assertEquals(Tier.NORMAL, tierOf(state(30)))
        assertEquals(Tier.SAVER, tierOf(state(29)))
        assertEquals(Tier.SAVER, tierOf(state(16)))
        assertEquals(Tier.CRITICAL, tierOf(state(15)))
        assertEquals(Tier.NORMAL, tierOf(state(5, charging = true)))
        assertEquals(Tier.NORMAL, tierOf(state(thermal = THERMAL_LIGHT)))
        assertEquals(Tier.SAVER, tierOf(state(thermal = THERMAL_MODERATE)))
        assertEquals(Tier.CRITICAL, tierOf(state(thermal = THERMAL_SEVERE)))
        assertEquals(Tier.SAVER, tierOf(state(saver = true)))
        assertEquals(Tier.CRITICAL, tierOf(state(5, charging = true, thermal = 4)))
    }

    @Test
    fun noSilentDegradeWithoutConsent() {
        val low = state(20)
        assertEquals(PowerPolicy.NORMAL, choosePolicy(low, Consent.UNDECIDED))
        assertEquals(PowerPolicy.NORMAL, choosePolicy(low, Consent.KEEP))
        assertEquals(PowerPolicy.SAVER, choosePolicy(low, Consent.REDUCE))
        assertEquals(PowerPolicy.CRITICAL, choosePolicy(state(10), Consent.REDUCE))
        assertEquals(PowerPolicy.NORMAL, choosePolicy(state(10), Consent.KEEP))
    }

    @Test
    fun severeThermalDegradesRegardlessOfConsent() {
        val hot = state(90, thermal = THERMAL_SEVERE)
        for (c in Consent.entries) assertEquals(PowerPolicy.CRITICAL, choosePolicy(hot, c))
        assertTrue(mustDegrade(hot))
        assertFalse(needsPrompt(hot, Consent.UNDECIDED)) // told, not asked
    }

    @Test
    fun promptOnlyWhenWorseTierAndUndecided() {
        assertFalse(needsPrompt(state(80), Consent.UNDECIDED))
        assertTrue(needsPrompt(state(20), Consent.UNDECIDED))
        assertFalse(needsPrompt(state(20), Consent.KEEP))
        assertFalse(needsPrompt(state(20), Consent.REDUCE))
    }

    @Test
    fun policyOrderingIsMonotonic() {
        val n = PowerPolicy.NORMAL
        val s = PowerPolicy.SAVER
        val c = PowerPolicy.CRITICAL
        assertTrue(n.analysisFpsCap > s.analysisFpsCap && s.analysisFpsCap > c.analysisFpsCap)
        assertTrue(n.animationScale > s.animationScale && s.animationScale > c.animationScale)
        assertTrue(n.analysisResolution.width > s.analysisResolution.width)
        assertTrue(s.analysisResolution.width > c.analysisResolution.width)
        assertEquals(OverlayDetail.FULL, n.overlayDetail)
        assertEquals(OverlayDetail.MINIMAL, s.overlayDetail)
    }

    @Test
    fun thermalFallbackFromBatteryTemperature() {
        assertEquals(0, thermalFromTemperature(350))
        assertEquals(THERMAL_LIGHT, thermalFromTemperature(400))
        assertEquals(THERMAL_MODERATE, thermalFromTemperature(420))
        assertEquals(THERMAL_SEVERE, thermalFromTemperature(450))
        assertEquals(2, resolveThermal(2, 500)) // API status wins when present
        assertEquals(THERMAL_SEVERE, resolveThermal(null, 460))
    }

    @Test
    fun systemReduceMotionWins() {
        assertEquals(0f, effectiveAnimationScale(1f, 0f), 0f)
        assertEquals(0.5f, effectiveAnimationScale(0.5f, 2f), 0f)
        assertEquals(1f, effectiveAnimationScale(1f, 1f), 0f)
    }

    private fun passed(fps: Int, cameraFps: Int, seconds: Int, jitterMs: Int = 0): Int {
        val throttle = FrameThrottle(fps)
        var count = 0
        var t = 1_000L
        val end = t + seconds * 1000L
        var i = 0
        while (t < end) {
            if (throttle.allow(t)) count++
            i++
            t = 1_000L + i * 1000L / cameraFps + if (i % 2 == 0) jitterMs else -jitterMs
        }
        return count
    }

    @Test
    fun throttleCapsRate() {
        assertEquals(15, passed(fps = 15, cameraFps = 30, seconds = 1))
        assertEquals(15, passed(fps = 15, cameraFps = 30, seconds = 1, jitterMs = 3))
        assertEquals(10, passed(fps = 10, cameraFps = 30, seconds = 1))
        assertEquals(30, passed(fps = 30, cameraFps = 30, seconds = 1, jitterMs = 3))
    }

    @Test
    fun throttleFirstFrameAlwaysPassesAndCapCanChange() {
        val t = FrameThrottle(10)
        assertTrue(t.allow(0L))
        assertFalse(t.allow(10L))
        t.setCap(0) // 0 = uncapped
        assertTrue(t.allow(11L))
        assertTrue(t.allow(12L))
    }
}
