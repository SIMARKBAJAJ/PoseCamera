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
}

data class RepSetProgress(
    val movementState: RepMovementState,
    val attemptedReps: Int,
    val cleanReps: Int,
    val rejectedReps: Int,
    val rejectionReasons: List<String>,
)

data class RepSetSummary(
    val attemptedReps: Int,
    val cleanReps: Int,
    val rejectedReps: Int,
    val formScore: Float,
    val mostCommonRejectionReason: String?,
    val rejectionReasons: List<String>,
)

class RepCounter {
    private var movementState = RepMovementState.STANDING
    private var pendingState: RepMovementState? = null
    private var pendingFrames = 0
    private var cycleStartedAtMillis: Long? = null
    private var downPhaseMillis: Long? = null
    private var reachedGreenDepth = false
    private var sawRedValgus = false
    private var attemptedReps = 0
    private var cleanReps = 0
    private val rejectionReasons = mutableListOf<String>()

    fun onFrame(form: SquatFormResult, timestampMillis: Long): RepSetProgress {
        val angle = form.worstKneeAngle()
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
        if (cycleStartedAtMillis != null) observeCycle(form)

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
        if (pendingFrames >= TRANSITION_CONFIRMATION_FRAMES) {
            transitionTo(targetState, timestampMillis)
            clearPendingTransition()
        }
        return progress()
    }

    fun endSet(): RepSetSummary {
        val summary = RepSetSummary(
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

    private fun targetState(angle: Float): RepMovementState? = when (movementState) {
        RepMovementState.STANDING ->
            RepMovementState.DESCENDING.takeIf { angle <= DESCENDING_START_MAX_DEGREES }

        RepMovementState.DESCENDING -> when {
            angle <= BOTTOM_ENTRY_MAX_DEGREES -> RepMovementState.BOTTOM
            angle >= STANDING_MIN_DEGREES -> RepMovementState.STANDING
            else -> null
        }

        RepMovementState.BOTTOM ->
            RepMovementState.ASCENDING.takeIf { angle >= ASCENDING_START_MIN_DEGREES }

        RepMovementState.ASCENDING ->
            RepMovementState.STANDING.takeIf { angle >= STANDING_MIN_DEGREES }
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
        reachedGreenDepth = false
        sawRedValgus = false
    }

    private fun observeCycle(form: SquatFormResult) {
        if (form.state == SquatFormState.GREEN) reachedGreenDepth = true
        val leftValgus = form.leftKneeValgusOffset ?: 0f
        val rightValgus = form.rightKneeValgusOffset ?: 0f
        if (leftValgus >= RED_KNEE_VALGUS_OFFSET || rightValgus >= RED_KNEE_VALGUS_OFFSET) {
            sawRedValgus = true
        }
    }

    private fun completeRep() {
        attemptedReps += 1
        val reasons = buildList {
            if (!reachedGreenDepth) add(RepRejectionReason.NOT_DEEP_ENOUGH.label)
            if (sawRedValgus) add(RepRejectionReason.KNEE_CAVING_IN.label)
            if ((downPhaseMillis ?: 0L) < MIN_DOWN_PHASE_MILLIS) {
                add(RepRejectionReason.TOO_FAST.label)
            }
        }
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
        reachedGreenDepth = false
        sawRedValgus = false
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

    private fun SquatFormResult.worstKneeAngle(): Float? {
        val left = leftKneeAngleDegrees ?: return null
        val right = rightKneeAngleDegrees ?: return null
        return maxOf(left, right).takeIf { it.isFinite() }
    }
}
