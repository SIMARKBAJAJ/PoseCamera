package com.example.posecamera.ui.library

import com.example.posecamera.demo.DemoLift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyweightDemoMappingTest {
    @Test fun catalogPushupSlugsMapToPushUp() {
        for (s in listOf("pushups", "push-up", "push-ups", "pushup")) assertEquals(s, DemoLift.PUSH_UP, demoOf(s))
    }

    @Test fun catalogSquatSlugsMapToSquat() {
        for (s in listOf("bodyweight-squat", "bodyweight-squats", "air-squat", "squat")) assertEquals(s, DemoLift.SQUAT, demoOf(s))
    }

    @Test fun nearMissesStayUnmapped() {
        assertNull(demoOf("push-up-wide"))
        assertNull(demoOf("squat-jerk"))
    }
}
