package com.example.posecamera.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class FpsTrackerTest {
    @Test
    fun reportsCompletedResultsPerSecond() {
        val tracker = FpsTracker(windowMillis = 1_000L)
        tracker.record(0L)
        tracker.record(100L)
        tracker.record(200L)
        assertEquals(10f, tracker.record(300L), 0.001f)
    }

    @Test
    fun discardsSamplesOutsideWindow() {
        val tracker = FpsTracker(windowMillis = 1_000L)
        tracker.record(0L)
        tracker.record(500L)
        assertEquals(2f, tracker.record(1_000L), 0.001f)
        assertEquals(2f, tracker.record(1_500L), 0.001f)
    }
}
