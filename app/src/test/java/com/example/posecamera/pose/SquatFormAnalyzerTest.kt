package com.example.posecamera.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SquatFormAnalyzerTest {
    @Test
    fun calculatesStraightAndRightAngles() {
        val knee = PosePoint(0.5f, 0.5f)
        val straight = angleDegrees(
            PosePoint(0.5f, 0.25f),
            knee,
            PosePoint(0.5f, 0.75f),
            100,
            100,
        )
        val right = angleDegrees(
            PosePoint(0.5f, 0.25f),
            knee,
            PosePoint(0.75f, 0.5f),
            100,
            100,
        )

        assertEquals(180f, straight!!, 0.001f)
        assertEquals(90f, right!!, 0.001f)
    }

    @Test
    fun correctsAngleForFrameAspectRatio() {
        val angle = angleDegrees(
            PosePoint(0.6f, 0.3f),
            PosePoint(0.5f, 0.5f),
            PosePoint(0.6f, 0.7f),
            200,
            100,
        )

        assertEquals(90f, angle!!, 0.001f)
    }

    @Test
    fun returnsNoAngleForDegenerateVector() {
        val knee = PosePoint(0.5f, 0.5f)
        assertNull(angleDegrees(knee, knee, PosePoint(0.5f, 0.8f), 100, 100))
    }

    @Test
    fun classifiesDepthBoundaries() {
        assertEquals(
            FormState.GREEN,
            classifySquatForm(89.9f, 89.9f, 0.04f, 0.04f),
        )
        assertEquals(
            FormState.YELLOW,
            classifySquatForm(90f, 89f, 0f, 0f),
        )
        assertEquals(
            FormState.RED,
            classifySquatForm(109f, 110f, 0f, 0f),
        )
    }

    @Test
    fun classifiesValgusBoundaries() {
        assertEquals(
            FormState.GREEN,
            classifySquatForm(80f, 80f, 0.04f, 0.04f),
        )
        assertEquals(
            FormState.YELLOW,
            classifySquatForm(80f, 80f, 0.05f, 0f),
        )
        assertEquals(
            FormState.RED,
            classifySquatForm(80f, 80f, 0f, 0.10f),
        )
    }

    @Test
    fun usesWorstLegForFrameState() {
        assertEquals(
            FormState.RED,
            classifySquatForm(80f, 115f, 0f, 0f),
        )
    }

    @Test
    fun measuresInwardOffsetFromEitherSideOfBody() {
        assertEquals(0.04f, inwardKneeOffset(0.76f, 0.80f, 0.50f), 0.0001f)
        assertEquals(0.04f, inwardKneeOffset(0.24f, 0.20f, 0.50f), 0.0001f)
        assertEquals(0f, inwardKneeOffset(0.84f, 0.80f, 0.50f), 0.0001f)
    }

    @Test
    fun lowVisibilityOrPresenceIsGrey() {
        val lowVisibility = validPose().also {
            it[LEFT_KNEE] = it[LEFT_KNEE].copy(visibility = 0.59f)
        }
        val lowPresence = validPose().also {
            it[RIGHT_ANKLE] = it[RIGHT_ANKLE].copy(presence = 0.59f)
        }

        assertEquals(FormState.GREY, analyzeSquatForm(lowVisibility, 100, 100).state)
        assertEquals(FormState.GREY, analyzeSquatForm(lowPresence, 100, 100).state)
    }

    @Test
    fun minimumConfidenceBoundaryIsJudgeable() {
        val boundaryPose = validPose().map {
            it.copy(
                visibility = MIN_LANDMARK_VISIBILITY,
                presence = MIN_LANDMARK_PRESENCE,
            )
        }

        assertEquals(FormState.RED, analyzeSquatForm(boundaryPose, 100, 100).state)
    }

    @Test
    fun missingOrDegenerateLandmarksAreGrey() {
        val missingAnkle = validPose().take(RIGHT_ANKLE)
        val degenerate = validPose().also { it[LEFT_HIP] = it[LEFT_KNEE] }

        assertEquals(FormState.GREY, analyzeSquatForm(missingAnkle, 100, 100).state)
        assertEquals(FormState.GREY, analyzeSquatForm(degenerate, 100, 100).state)
    }

    @Test
    fun horizontalMirroringPreservesClassification() {
        val original = validPose()
        val mirrored = original.map { it.copy(x = 1f - it.x) }

        val originalResult = analyzeSquatForm(original, 100, 100)
        val mirroredResult = analyzeSquatForm(mirrored, 100, 100)

        assertEquals(originalResult.state, mirroredResult.state)
        assertEquals(
            originalResult.leftKneeAngleDegrees!!,
            mirroredResult.leftKneeAngleDegrees!!,
            0.001f,
        )
        assertEquals(
            originalResult.rightKneeAngleDegrees!!,
            mirroredResult.rightKneeAngleDegrees!!,
            0.001f,
        )
    }

    private fun validPose(): MutableList<PosePoint> = MutableList(29) {
        PosePoint(0.5f, 0.5f)
    }.also {
        it[LEFT_HIP] = PosePoint(0.35f, 0.30f)
        it[RIGHT_HIP] = PosePoint(0.65f, 0.30f)
        it[LEFT_KNEE] = PosePoint(0.35f, 0.50f)
        it[RIGHT_KNEE] = PosePoint(0.65f, 0.50f)
        it[LEFT_ANKLE] = PosePoint(0.35f, 0.75f)
        it[RIGHT_ANKLE] = PosePoint(0.65f, 0.75f)
    }

    private companion object {
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
    }
}
