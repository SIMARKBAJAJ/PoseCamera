package com.example.posecamera.ui

import com.example.posecamera.power.PowerPolicy
import com.example.posecamera.power.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerLabelTest {
    @Test fun tierShowsRawTierEvenWhenConsentKeepsFullPolicy() {
        assertEquals("SAVER", tierLabel(Tier.SAVER))
        assertEquals("CRITICAL", tierLabel(Tier.CRITICAL))
        assertEquals("NORMAL", tierLabel(Tier.NORMAL))
    }

    @Test fun cameraQualityFollowsPolicy() {
        assertEquals("Camera quality: full", cameraQualityLabel(PowerPolicy.NORMAL))
        assertEquals("Camera quality: reduced", cameraQualityLabel(PowerPolicy.SAVER))
        assertEquals("Camera quality: reduced", cameraQualityLabel(PowerPolicy.CRITICAL))
    }
}
