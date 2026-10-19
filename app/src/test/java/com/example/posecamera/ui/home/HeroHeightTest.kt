package com.example.posecamera.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroHeightTest {
    @Test fun cardStaysNearFortyFivePercent() {
        for (h in listOf(640, 780, 900)) {
            val card = heroFigureHeightDp(h, true) + 104f // figure area + header, rule, caption, padding
            assertTrue("$h -> $card", card <= h * 0.45f + 1f)
        }
    }

    @Test fun noCaptionLeavesRoomForTheButton() {
        assertTrue(heroFigureHeightDp(800, false) < heroFigureHeightDp(800, true))
        assertEquals(160f, heroFigureHeightDp(300, true), 0f) // floor
    }
}
