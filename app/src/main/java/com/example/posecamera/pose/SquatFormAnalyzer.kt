package com.example.posecamera.pose

import kotlin.math.acos
import kotlin.math.hypot

// Both knee angles must be below this value for adequate squat depth.
const val ADEQUATE_DEPTH_MAX_DEGREES = 90f

// Either knee angle at or above this value makes the squat clearly too shallow.
const val RED_DEPTH_MIN_DEGREES = 110f

// Inward knee drift up to this fraction of frame width is treated as aligned.
const val ALIGNED_KNEE_ANKLE_MAX_OFFSET = 0.04f

// Inward knee drift at or above this frame-width fraction is significant valgus.
const val RED_KNEE_VALGUS_OFFSET = 0.10f

// Every hip, knee, and ankle must meet this visibility score to classify the frame.
const val MIN_LANDMARK_VISIBILITY = 0.60f

// Every hip, knee, and ankle must meet this presence score to classify the frame.
const val MIN_LANDMARK_PRESENCE = 0.60f

data class SquatFormResult(
    override val state: FormState,
    val leftKneeAngleDegrees: Float?,
    val rightKneeAngleDegrees: Float?,
    val leftKneeValgusOffset: Float?,
    val rightKneeValgusOffset: Float?,
) : ExerciseAnalysis {
    override val exerciseType = ExerciseType.SQUAT
    override val movementAngleDegrees: Float?
        get() {
            val left = leftKneeAngleDegrees ?: return null
            val right = rightKneeAngleDegrees ?: return null
            return maxOf(left, right).takeIf { it.isFinite() }
        }
    override val bodyDeviationDegrees: Float? = null
    override val maxKneeValgusOffset: Float?
        get() {
            val left = leftKneeValgusOffset ?: return null
            val right = rightKneeValgusOffset ?: return null
            return maxOf(left, right).takeIf { it.isFinite() }
        }
    override val activeLandmarks = SQUAT_LANDMARKS
    override val activeConnections = SQUAT_CONNECTIONS

    companion object {
        val Unjudgeable = SquatFormResult(
            state = FormState.GREY,
            leftKneeAngleDegrees = null,
            rightKneeAngleDegrees = null,
            leftKneeValgusOffset = null,
            rightKneeValgusOffset = null,
        )
    }
}

fun analyzeSquatForm(
    landmarks: List<PosePoint>,
    imageWidth: Int,
    imageHeight: Int,
): SquatFormResult {
    if (imageWidth <= 0 || imageHeight <= 0 || landmarks.size <= RIGHT_ANKLE) {
        return SquatFormResult.Unjudgeable
    }

    val required = REQUIRED_LANDMARKS.map(landmarks::get)
    if (required.any { !it.isReliablePosePoint() }) return SquatFormResult.Unjudgeable

    val leftHip = landmarks[LEFT_HIP]
    val rightHip = landmarks[RIGHT_HIP]
    val leftKnee = landmarks[LEFT_KNEE]
    val rightKnee = landmarks[RIGHT_KNEE]
    val leftAnkle = landmarks[LEFT_ANKLE]
    val rightAnkle = landmarks[RIGHT_ANKLE]

    val leftAngle = angleDegrees(leftHip, leftKnee, leftAnkle, imageWidth, imageHeight)
        ?: return SquatFormResult.Unjudgeable
    val rightAngle = angleDegrees(rightHip, rightKnee, rightAnkle, imageWidth, imageHeight)
        ?: return SquatFormResult.Unjudgeable
    val hipMidpointX = (leftHip.x + rightHip.x) / 2f
    val leftValgus = inwardKneeOffset(leftKnee.x, leftAnkle.x, hipMidpointX)
    val rightValgus = inwardKneeOffset(rightKnee.x, rightAnkle.x, hipMidpointX)

    val state = classifySquatForm(leftAngle, rightAngle, leftValgus, rightValgus)

    return SquatFormResult(
        state = state,
        leftKneeAngleDegrees = leftAngle,
        rightKneeAngleDegrees = rightAngle,
        leftKneeValgusOffset = leftValgus,
        rightKneeValgusOffset = rightValgus,
    )
}

internal fun classifySquatForm(
    leftAngle: Float,
    rightAngle: Float,
    leftValgus: Float,
    rightValgus: Float,
): FormState = when {
    leftAngle >= RED_DEPTH_MIN_DEGREES ||
        rightAngle >= RED_DEPTH_MIN_DEGREES ||
        leftValgus >= RED_KNEE_VALGUS_OFFSET ||
        rightValgus >= RED_KNEE_VALGUS_OFFSET -> FormState.RED

    leftAngle < ADEQUATE_DEPTH_MAX_DEGREES &&
        rightAngle < ADEQUATE_DEPTH_MAX_DEGREES &&
        leftValgus <= ALIGNED_KNEE_ANKLE_MAX_OFFSET &&
        rightValgus <= ALIGNED_KNEE_ANKLE_MAX_OFFSET -> FormState.GREEN

    else -> FormState.YELLOW
}

internal fun angleDegrees(
    first: PosePoint,
    vertex: PosePoint,
    third: PosePoint,
    imageWidth: Int,
    imageHeight: Int,
): Float? {
    val firstX = (first.x - vertex.x) * imageWidth
    val firstY = (first.y - vertex.y) * imageHeight
    val thirdX = (third.x - vertex.x) * imageWidth
    val thirdY = (third.y - vertex.y) * imageHeight
    val firstLength = hypot(firstX, firstY)
    val thirdLength = hypot(thirdX, thirdY)
    if (firstLength == 0f || thirdLength == 0f) return null

    val cosine = ((firstX * thirdX + firstY * thirdY) / (firstLength * thirdLength))
        .coerceIn(-1f, 1f)
    val angle = Math.toDegrees(acos(cosine.toDouble())).toFloat()
    return angle.takeIf { it.isFinite() }
}

internal fun inwardKneeOffset(kneeX: Float, ankleX: Float, hipMidpointX: Float): Float =
    if (ankleX >= hipMidpointX) {
        (ankleX - kneeX).coerceAtLeast(0f)
    } else {
        (kneeX - ankleX).coerceAtLeast(0f)
    }

internal fun PosePoint.isReliablePosePoint(): Boolean =
    x.isFinite() && y.isFinite() &&
        visibility.isFinite() && visibility >= MIN_LANDMARK_VISIBILITY &&
        presence.isFinite() && presence >= MIN_LANDMARK_PRESENCE

private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24
private const val LEFT_KNEE = 25
private const val RIGHT_KNEE = 26
private const val LEFT_ANKLE = 27
private const val RIGHT_ANKLE = 28
private val REQUIRED_LANDMARKS = intArrayOf(
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE,
)
private val SQUAT_LANDMARKS = setOf(LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE)
private val SQUAT_CONNECTIONS = setOf(
    connectionKey(LEFT_HIP, RIGHT_HIP),
    connectionKey(LEFT_HIP, LEFT_KNEE),
    connectionKey(LEFT_KNEE, LEFT_ANKLE),
    connectionKey(RIGHT_HIP, RIGHT_KNEE),
    connectionKey(RIGHT_KNEE, RIGHT_ANKLE),
)
