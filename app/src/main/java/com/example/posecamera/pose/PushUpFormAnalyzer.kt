package com.example.posecamera.pose

const val PUSH_UP_LOWERING_START_DEGREES = 150f
const val PUSH_UP_BOTTOM_DEGREES = 90f
const val PUSH_UP_ASCENDING_START_DEGREES = 120f
const val PUSH_UP_LOCKOUT_DEGREES = 160f
const val PUSH_UP_BODY_GREEN_MAX_DEGREES = 10f
const val PUSH_UP_BODY_TOLERANCE_DEGREES = 20f

enum class BodySide {
    LEFT,
    RIGHT,
}

data class PushUpFormResult(
    override val state: FormState,
    val side: BodySide?,
    val elbowAngleDegrees: Float?,
    val bodyLineAngleDegrees: Float?,
    override val bodyDeviationDegrees: Float?,
    override val activeLandmarks: Set<Int>,
    override val activeConnections: Set<Pair<Int, Int>>,
) : ExerciseAnalysis {
    override val exerciseType = ExerciseType.PUSH_UP
    override val movementAngleDegrees get() = elbowAngleDegrees
    override val maxKneeValgusOffset: Float? = null

    companion object {
        val Unjudgeable = PushUpFormResult(
            state = FormState.GREY,
            side = null,
            elbowAngleDegrees = null,
            bodyLineAngleDegrees = null,
            bodyDeviationDegrees = null,
            activeLandmarks = emptySet(),
            activeConnections = emptySet(),
        )
    }
}

fun analyzePushUpForm(
    landmarks: List<PosePoint>,
    imageWidth: Int,
    imageHeight: Int,
): PushUpFormResult {
    if (imageWidth <= 0 || imageHeight <= 0 || landmarks.size <= RIGHT_ANKLE) {
        return PushUpFormResult.Unjudgeable
    }

    val side = selectPushUpSide(landmarks)
    val indices = if (side == BodySide.LEFT) LEFT_SIDE else RIGHT_SIDE
    val required = indices.map(landmarks::get)
    val activeLandmarks = indices.toSet()
    val activeConnections = setOf(
        connectionKey(indices[0], indices[1]),
        connectionKey(indices[1], indices[2]),
        connectionKey(indices[0], indices[3]),
        connectionKey(indices[3], indices[4]),
        connectionKey(indices[4], indices[5]),
    )
    if (required.any { !it.isReliablePosePoint() }) {
        return PushUpFormResult(
            state = FormState.GREY,
            side = side,
            elbowAngleDegrees = null,
            bodyLineAngleDegrees = null,
            bodyDeviationDegrees = null,
            activeLandmarks = activeLandmarks,
            activeConnections = activeConnections,
        )
    }

    val shoulder = required[0]
    val elbow = required[1]
    val wrist = required[2]
    val hip = required[3]
    val ankle = required[5]
    val elbowAngle = angleDegrees(shoulder, elbow, wrist, imageWidth, imageHeight)
        ?: return PushUpFormResult.Unjudgeable
    val bodyAngle = angleDegrees(shoulder, hip, ankle, imageWidth, imageHeight)
        ?: return PushUpFormResult.Unjudgeable
    val bodyDeviation = 180f - bodyAngle

    return PushUpFormResult(
        state = classifyPushUpBodyLine(bodyDeviation),
        side = side,
        elbowAngleDegrees = elbowAngle,
        bodyLineAngleDegrees = bodyAngle,
        bodyDeviationDegrees = bodyDeviation,
        activeLandmarks = activeLandmarks,
        activeConnections = activeConnections,
    )
}

internal fun selectPushUpSide(landmarks: List<PosePoint>): BodySide {
    val left = sideConfidence(landmarks, LEFT_SIDE)
    val right = sideConfidence(landmarks, RIGHT_SIDE)
    return if (right > left) BodySide.RIGHT else BodySide.LEFT
}

internal fun classifyPushUpBodyLine(deviationDegrees: Float): FormState = when {
    deviationDegrees <= PUSH_UP_BODY_GREEN_MAX_DEGREES -> FormState.GREEN
    deviationDegrees <= PUSH_UP_BODY_TOLERANCE_DEGREES -> FormState.YELLOW
    else -> FormState.RED
}

private fun sideConfidence(landmarks: List<PosePoint>, indices: IntArray): Float =
    indices.minOf { index ->
        val point = landmarks[index]
        minOf(point.visibility, point.presence).takeIf { it.isFinite() } ?: 0f
    }

private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_ELBOW = 13
private const val RIGHT_ELBOW = 14
private const val LEFT_WRIST = 15
private const val RIGHT_WRIST = 16
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24
private const val LEFT_KNEE = 25
private const val RIGHT_KNEE = 26
private const val LEFT_ANKLE = 27
private const val RIGHT_ANKLE = 28
private val LEFT_SIDE = intArrayOf(LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST, LEFT_HIP, LEFT_KNEE, LEFT_ANKLE)
private val RIGHT_SIDE = intArrayOf(RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST, RIGHT_HIP, RIGHT_KNEE, RIGHT_ANKLE)
