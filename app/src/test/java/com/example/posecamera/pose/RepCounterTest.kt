package com.example.posecamera.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepCounterTest {
    private var timestamp = 0L

    @Test
    fun confirmsTransitionsAcrossConsecutiveFrames() {
        val counter = RepCounter()

        advance(counter, angle = 140f, frames = 2)
        assertEquals(RepMovementState.STANDING, counter.progress().movementState)
        advance(counter, angle = 140f, frames = 1)
        assertEquals(RepMovementState.DESCENDING, counter.progress().movementState)

        advance(counter, angle = 100f)
        assertEquals(RepMovementState.BOTTOM, counter.progress().movementState)
        advance(counter, angle = 130f)
        assertEquals(RepMovementState.ASCENDING, counter.progress().movementState)
        advance(counter, angle = 170f)
        assertEquals(RepMovementState.STANDING, counter.progress().movementState)
    }

    @Test
    fun countsACompleteValidCycleAsClean() {
        val counter = RepCounter()
        completeRep(counter, bottomState = SquatFormState.GREEN, bottomAngle = 80f)

        val summary = counter.endSet()
        assertEquals(1, summary.attemptedReps)
        assertEquals(1, summary.cleanReps)
        assertEquals(0, summary.rejectedReps)
        assertEquals(100f, summary.formScore, 0.001f)
        assertTrue(summary.rejectionReasons.isEmpty())
    }

    @Test
    fun rejectsARepThatNeverReachesGreenDepth() {
        val counter = RepCounter()
        completeRep(counter, bottomState = SquatFormState.YELLOW, bottomAngle = 100f)

        val summary = counter.endSet()
        assertEquals(1, summary.rejectedReps)
        assertEquals(listOf("not deep enough"), summary.rejectionReasons)
    }

    @Test
    fun rejectsValgusSeenAnywhereInCycle() {
        val counter = RepCounter()
        completeRep(
            counter,
            bottomState = SquatFormState.GREEN,
            bottomAngle = 80f,
            descentValgus = RED_KNEE_VALGUS_OFFSET,
        )

        assertEquals(
            listOf("knee caving in"),
            counter.endSet().rejectionReasons,
        )
    }

    @Test
    fun rejectsDownPhaseBelowMinimumDuration() {
        val counter = RepCounter()
        completeRep(
            counter,
            bottomState = SquatFormState.GREEN,
            bottomAngle = 80f,
            downFrameIntervalMillis = 50L,
        )

        assertEquals(listOf("too fast"), counter.endSet().rejectionReasons)
    }

    @Test
    fun storesAllReasonsAndFindsMostCommonReason() {
        val counter = RepCounter()
        completeRep(counter, bottomState = SquatFormState.GREEN, bottomAngle = 80f)
        completeRep(counter, bottomState = SquatFormState.YELLOW, bottomAngle = 100f)
        completeRep(
            counter,
            bottomState = SquatFormState.YELLOW,
            bottomAngle = 100f,
            descentValgus = RED_KNEE_VALGUS_OFFSET,
        )

        val summary = counter.endSet()
        assertEquals(3, summary.attemptedReps)
        assertEquals(1, summary.cleanReps)
        assertEquals(2, summary.rejectedReps)
        assertEquals(100f / 3f, summary.formScore, 0.001f)
        assertEquals("not deep enough", summary.mostCommonRejectionReason)
        assertEquals(
            listOf("not deep enough", "not deep enough", "knee caving in"),
            summary.rejectionReasons,
        )
    }

    @Test
    fun endSetResetsInMemoryCounters() {
        val counter = RepCounter()
        completeRep(counter, bottomState = SquatFormState.GREEN, bottomAngle = 80f)
        counter.endSet()

        val progress = counter.progress()
        assertEquals(0, progress.attemptedReps)
        assertEquals(0, progress.cleanReps)
        assertEquals(0, progress.rejectedReps)
        assertEquals(RepMovementState.STANDING, progress.movementState)
    }

    @Test
    fun abortedDescentDoesNotCountAsAnAttempt() {
        val counter = RepCounter()
        advance(counter, angle = 140f)
        advance(counter, angle = 170f)

        val summary = counter.endSet()
        assertEquals(0, summary.attemptedReps)
        assertEquals(0, summary.rejectedReps)
    }

    private fun completeRep(
        counter: RepCounter,
        bottomState: SquatFormState,
        bottomAngle: Float,
        descentValgus: Float = 0f,
        downFrameIntervalMillis: Long = 250L,
    ) {
        advance(
            counter,
            angle = 140f,
            valgus = descentValgus,
            intervalMillis = downFrameIntervalMillis,
        )
        advance(
            counter,
            angle = bottomAngle,
            state = bottomState,
            intervalMillis = downFrameIntervalMillis,
        )
        advance(counter, angle = 130f)
        advance(counter, angle = 170f)
    }

    private fun advance(
        counter: RepCounter,
        angle: Float,
        state: SquatFormState = SquatFormState.YELLOW,
        valgus: Float = 0f,
        intervalMillis: Long = 100L,
        frames: Int = TRANSITION_CONFIRMATION_FRAMES,
    ) {
        repeat(frames) {
            timestamp += intervalMillis
            counter.onFrame(
                form(angle = angle, state = state, valgus = valgus),
                timestamp,
            )
        }
    }

    private fun form(
        angle: Float,
        state: SquatFormState,
        valgus: Float,
    ) = SquatFormResult(
        state = state,
        leftKneeAngleDegrees = angle,
        rightKneeAngleDegrees = angle,
        leftKneeValgusOffset = valgus,
        rightKneeValgusOffset = 0f,
    )
}
