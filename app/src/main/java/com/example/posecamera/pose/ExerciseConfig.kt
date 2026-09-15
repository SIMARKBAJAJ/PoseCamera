package com.example.posecamera.pose

enum class ExerciseType(val displayLabel: String) {
    SQUAT("Squat"),
    PUSH_UP("Push-up"),
}

enum class FormState {
    GREEN,
    YELLOW,
    RED,
    GREY,
}

interface ExerciseAnalysis {
    val exerciseType: ExerciseType
    val state: FormState
    val movementAngleDegrees: Float?
    val bodyDeviationDegrees: Float?
    val maxKneeValgusOffset: Float?
    val activeLandmarks: Set<Int>
    val activeConnections: Set<Pair<Int, Int>>
}

data class RepRules(
    val loweringStartDegrees: Float,
    val bottomEntryDegrees: Float,
    val ascendingStartDegrees: Float,
    val lockoutDegrees: Float,
    val transitionFrames: Int = TRANSITION_CONFIRMATION_FRAMES,
)

interface ExerciseConfig {
    val type: ExerciseType
    val guidance: String?
    val repRules: RepRules

    fun analyze(landmarks: List<PosePoint>, imageWidth: Int, imageHeight: Int): ExerciseAnalysis

    fun isCleanDepth(analysis: ExerciseAnalysis): Boolean

    fun rejectionReasons(evidence: RepCycleEvidence): List<String>
}

data class RepCycleEvidence(
    val reachedCleanDepth: Boolean,
    val maximumBodyDeviationDegrees: Float?,
    val maximumKneeValgusOffset: Float?,
    val downPhaseMillis: Long?,
)

object SquatExerciseConfig : ExerciseConfig {
    override val type = ExerciseType.SQUAT
    override val guidance: String? = null
    override val repRules = RepRules(
        loweringStartDegrees = DESCENDING_START_MAX_DEGREES,
        bottomEntryDegrees = BOTTOM_ENTRY_MAX_DEGREES,
        ascendingStartDegrees = ASCENDING_START_MIN_DEGREES,
        lockoutDegrees = STANDING_MIN_DEGREES,
    )

    override fun analyze(landmarks: List<PosePoint>, imageWidth: Int, imageHeight: Int) =
        analyzeSquatForm(landmarks, imageWidth, imageHeight)

    override fun isCleanDepth(analysis: ExerciseAnalysis) = analysis.state == FormState.GREEN

    override fun rejectionReasons(evidence: RepCycleEvidence) = buildList {
        if (!evidence.reachedCleanDepth) add(RepRejectionReason.NOT_DEEP_ENOUGH.label)
        if ((evidence.maximumKneeValgusOffset ?: 0f) >= RED_KNEE_VALGUS_OFFSET) {
            add(RepRejectionReason.KNEE_CAVING_IN.label)
        }
        if ((evidence.downPhaseMillis ?: 0L) < MIN_DOWN_PHASE_MILLIS) {
            add(RepRejectionReason.TOO_FAST.label)
        }
    }
}

object PushUpExerciseConfig : ExerciseConfig {
    override val type = ExerciseType.PUSH_UP
    override val guidance = "Position phone to your side with your full body in frame."
    override val repRules = RepRules(
        loweringStartDegrees = PUSH_UP_LOWERING_START_DEGREES,
        bottomEntryDegrees = PUSH_UP_BOTTOM_DEGREES,
        ascendingStartDegrees = PUSH_UP_ASCENDING_START_DEGREES,
        lockoutDegrees = PUSH_UP_LOCKOUT_DEGREES,
    )

    override fun analyze(landmarks: List<PosePoint>, imageWidth: Int, imageHeight: Int) =
        analyzePushUpForm(landmarks, imageWidth, imageHeight)

    override fun isCleanDepth(analysis: ExerciseAnalysis) =
        analysis.movementAngleDegrees?.let { it <= PUSH_UP_BOTTOM_DEGREES } == true

    override fun rejectionReasons(evidence: RepCycleEvidence) = buildList {
        if (!evidence.reachedCleanDepth) add(RepRejectionReason.NOT_DEEP_ENOUGH.label)
        if ((evidence.maximumBodyDeviationDegrees ?: 0f) > PUSH_UP_BODY_TOLERANCE_DEGREES) {
            add(RepRejectionReason.BODY_LINE_OUT_OF_ALIGNMENT.label)
        }
    }
}

internal fun connectionKey(start: Int, end: Int): Pair<Int, Int> =
    minOf(start, end) to maxOf(start, end)
