package com.example.posecamera.pose

const val STANDING_MIN_DEGREES = 160f
const val DESCENDING_START_MAX_DEGREES = 145f
const val BOTTOM_ENTRY_MAX_DEGREES = RED_DEPTH_MIN_DEGREES
const val ASCENDING_START_MIN_DEGREES = 120f
const val MIN_DOWN_PHASE_MILLIS = 600L
const val TRANSITION_CONFIRMATION_FRAMES = 3

enum class RepMovementState {
    STANDING,
    DESCENDING,
    BOTTOM,
    ASCENDING,
}

enum class RepRejectionReason(val label: String) {
    NOT_DEEP_ENOUGH("not deep enough"),
    KNEE_CAVING_IN("knee caving in"),
    TOO_FAST("too fast"),
    BODY_LINE_OUT_OF_ALIGNMENT("body line out of alignment"),
}

data class RepSetProgress(
    val movementState: RepMovementState,
    val attemptedReps: Int,
    val cleanReps: Int,
    val rejectedReps: Int,
    val rejectionReasons: List<String>,
)

data class RepSetSummary(
    val exerciseType: ExerciseType,
    val attemptedReps: Int,
    val cleanReps: Int,
    val rejectedReps: Int,
    val formScore: Float,
    val mostCommonRejectionReason: String?,
    val rejectionReasons: List<String>,
)

class RepCounter(private val config: ExerciseConfig = SquatExerciseConfig) {
    private var movementState = RepMovementState.STANDING
    private var pendingState: RepMovementState? = null
    private var pendingFrames = 0
    private var cycleStartedAtMillis: Long? = null
    private var downPhaseMillis: Long? = null
    private var reachedCleanDepth = false
    private var maximumBodyDeviationDegrees: Float? = null
    private var maximumKneeValgusOffset: Float? = null
    private var attemptedReps = 0
    private var cleanReps = 0
    private val rejectionReasons = mutableListOf<String>()

    fun onFrame(analysis: ExerciseAnalysis, timestampMillis: Long): RepSetProgress {
        if (analysis.exerciseType != config.type) return progress()
        val angle = analysis.movementAngleDegrees
        if (angle == null) {
            if (movementState == RepMovementState.STANDING) resetCycle()
            clearPendingTransition()
            return progress()
        }

        val targetState = targetState(angle)
        if (
            movementState == RepMovementState.STANDING &&
            targetState == RepMovementState.DESCENDING &&
            cycleStartedAtMillis == null
        ) {
            startCycle(timestampMillis)
        }
        if (cycleStartedAtMillis != null) observeCycle(analysis)

        if (targetState == null) {
            if (movementState == RepMovementState.STANDING) resetCycle()
            clearPendingTransition()
            return progress()
        }

        if (pendingState == targetState) {
            pendingFrames += 1
        } else {
            pendingState = targetState
            pendingFrames = 1
        }
        if (pendingFrames >= config.repRules.transitionFrames) {
            transitionTo(targetState, timestampMillis)
            clearPendingTransition()
        }
        return progress()
    }

    fun endSet(): RepSetSummary {
        val summary = RepSetSummary(
            exerciseType = config.type,
            attemptedReps = attemptedReps,
            cleanReps = cleanReps,
            rejectedReps = attemptedReps - cleanReps,
            formScore = if (attemptedReps == 0) 0f else cleanReps * 100f / attemptedReps,
            mostCommonRejectionReason = mostCommonReason(),
            rejectionReasons = rejectionReasons.toList(),
        )
        resetSet()
        return summary
    }

    fun progress(): RepSetProgress = RepSetProgress(
        movementState = movementState,
        attemptedReps = attemptedReps,
        cleanReps = cleanReps,
        rejectedReps = attemptedReps - cleanReps,
        rejectionReasons = rejectionReasons.toList(),
    )

    private fun targetState(angle: Float): RepMovementState? {
        val rules = config.repRules
        return when (movementState) {
        RepMovementState.STANDING ->
            RepMovementState.DESCENDING.takeIf { angle <= rules.loweringStartDegrees }

        RepMovementState.DESCENDING -> when {
            angle <= rules.bottomEntryDegrees -> RepMovementState.BOTTOM
            angle >= rules.lockoutDegrees -> RepMovementState.STANDING
            else -> null
        }

        RepMovementState.BOTTOM ->
            RepMovementState.ASCENDING.takeIf { angle >= rules.ascendingStartDegrees }

        RepMovementState.ASCENDING ->
            RepMovementState.STANDING.takeIf { angle >= rules.lockoutDegrees }
        }
    }

    private fun transitionTo(next: RepMovementState, timestampMillis: Long) {
        val previous = movementState
        movementState = next
        when {
            previous == RepMovementState.DESCENDING && next == RepMovementState.BOTTOM -> {
                downPhaseMillis = timestampMillis - (cycleStartedAtMillis ?: timestampMillis)
            }

            previous == RepMovementState.ASCENDING && next == RepMovementState.STANDING -> {
                completeRep()
            }

            previous == RepMovementState.DESCENDING && next == RepMovementState.STANDING -> {
                resetCycle()
            }
        }
    }

    private fun startCycle(timestampMillis: Long) {
        cycleStartedAtMillis = timestampMillis
        downPhaseMillis = null
        reachedCleanDepth = false
        maximumBodyDeviationDegrees = null
        maximumKneeValgusOffset = null
    }

    private fun observeCycle(analysis: ExerciseAnalysis) {
        if (config.isCleanDepth(analysis)) reachedCleanDepth = true
        maximumBodyDeviationDegrees = maxNullable(
            maximumBodyDeviationDegrees,
            analysis.bodyDeviationDegrees,
        )
        maximumKneeValgusOffset = maxNullable(
            maximumKneeValgusOffset,
            analysis.maxKneeValgusOffset,
        )
    }

    private fun completeRep() {
        attemptedReps += 1
        val reasons = config.rejectionReasons(
            RepCycleEvidence(
                reachedCleanDepth = reachedCleanDepth,
                maximumBodyDeviationDegrees = maximumBodyDeviationDegrees,
                maximumKneeValgusOffset = maximumKneeValgusOffset,
                downPhaseMillis = downPhaseMillis,
            ),
        )
        if (reasons.isEmpty()) cleanReps += 1 else rejectionReasons += reasons
        resetCycle()
    }

    private fun mostCommonReason(): String? = rejectionReasons
        .groupingBy { it }
        .eachCount()
        .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        ?.key

    private fun resetCycle() {
        cycleStartedAtMillis = null
        downPhaseMillis = null
        reachedCleanDepth = false
        maximumBodyDeviationDegrees = null
        maximumKneeValgusOffset = null
    }

    private fun resetSet() {
        movementState = RepMovementState.STANDING
        clearPendingTransition()
        resetCycle()
        attemptedReps = 0
        cleanReps = 0
        rejectionReasons.clear()
    }

    private fun clearPendingTransition() {
        pendingState = null
        pendingFrames = 0
    }

    private fun maxNullable(current: Float?, next: Float?): Float? = when {
        next == null || !next.isFinite() -> current
        current == null -> next
        else -> maxOf(current, next)
    }
}
