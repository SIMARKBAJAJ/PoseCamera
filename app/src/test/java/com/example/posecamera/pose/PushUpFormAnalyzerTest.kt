package com.example.posecamera.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushUpFormAnalyzerTest {
    @Test
    fun selectsSideWithStrongestVisibilityAndPresence() {
        val pose = validPose().also {
            RIGHT_SIDE.forEach { index -> it[index] = it[index].copy(visibility = 0.9f, presence = 0.9f) }
        }

        assertEquals(BodySide.RIGHT, selectPushUpSide(pose))

        pose[RIGHT_KNEE] = pose[RIGHT_KNEE].copy(presence = 0.5f)
        assertEquals(BodySide.LEFT, selectPushUpSide(pose))
    }

    @Test
    fun classifiesBodyLineBoundaries() {
        assertEquals(FormState.GREEN, classifyPushUpBodyLine(10f))
        assertEquals(FormState.YELLOW, classifyPushUpBodyLine(10.01f))
        assertEquals(FormState.YELLOW, classifyPushUpBodyLine(20f))
        assertEquals(FormState.RED, classifyPushUpBodyLine(20.01f))
    }

    @Test
    fun calculatesElbowAndBodyLineWithActiveSideHighlights() {
        val result = analyzePushUpForm(validPose(), 100, 100)

        assertEquals(BodySide.LEFT, result.side)
        assertEquals(180f, result.elbowAngleDegrees!!, 0.001f)
        assertEquals(180f, result.bodyLineAngleDegrees!!, 0.001f)
        assertEquals(0f, result.bodyDeviationDegrees!!, 0.001f)
        assertEquals(FormState.GREEN, result.state)
        assertEquals(LEFT_SIDE.toSet(), result.activeLandmarks)
        assertEquals(
            setOf(
                connectionKey(LEFT_SHOULDER, LEFT_ELBOW),
                connectionKey(LEFT_ELBOW, LEFT_WRIST),
                connectionKey(LEFT_SHOULDER, LEFT_HIP),
                connectionKey(LEFT_HIP, LEFT_KNEE),
                connectionKey(LEFT_KNEE, LEFT_ANKLE),
            ),
            result.activeConnections,
        )
    }

    @Test
    fun lowConfidenceSelectedSideIsGrey() {
        val pose = validPose().also {
            it[LEFT_WRIST] = it[LEFT_WRIST].copy(visibility = 0.59f)
            RIGHT_SIDE.forEach { index -> it[index] = it[index].copy(visibility = 0.5f, presence = 0.5f) }
        }

        val result = analyzePushUpForm(pose, 100, 100)

        assertEquals(BodySide.LEFT, result.side)
        assertEquals(FormState.GREY, result.state)
        assertNull(result.elbowAngleDegrees)
        assertEquals(LEFT_SIDE.toSet(), result.activeLandmarks)
    }

    private fun validPose(): MutableList<PosePoint> = MutableList(29) {
        PosePoint(0.5f, 0.5f, visibility = 0.7f, presence = 0.7f)
    }.also {
        it[LEFT_SHOULDER] = PosePoint(0.2f, 0.5f, 0.8f, 0.8f)
        it[LEFT_ELBOW] = PosePoint(0.3f, 0.5f, 0.8f, 0.8f)
        it[LEFT_WRIST] = PosePoint(0.4f, 0.5f, 0.8f, 0.8f)
        it[LEFT_HIP] = PosePoint(0.5f, 0.5f, 0.8f, 0.8f)
        it[LEFT_ANKLE] = PosePoint(0.8f, 0.5f, 0.8f, 0.8f)
    }

    private companion object {
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
        val LEFT_SIDE = intArrayOf(LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST, LEFT_HIP, LEFT_KNEE, LEFT_ANKLE)
        val RIGHT_SIDE = intArrayOf(RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST, RIGHT_HIP, RIGHT_KNEE, RIGHT_ANKLE)
    }
}
