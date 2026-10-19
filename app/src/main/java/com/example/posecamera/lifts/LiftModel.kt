package com.example.posecamera.lifts

import com.example.posecamera.pose.ExerciseAnalysis
import com.example.posecamera.pose.ExerciseConfig
import com.example.posecamera.pose.ExerciseType
import com.example.posecamera.pose.FormState
import com.example.posecamera.pose.PosePoint
import com.example.posecamera.pose.RepCycleEvidence
import com.example.posecamera.pose.RepRules

// All numbers below are PROJECT DEFAULTS, not literature: form_research.md found zero citable
// numeric thresholds. Tune them on recorded clips of real people.

/** MediaPipe's documented default confidence (A1 in form_research.md). Raise to 0.6-0.7 if false alarms. */
const val MIN_VISIBILITY = 0.5f

/** A landmark must clear [MIN_VISIBILITY] this many consecutive frames before any fault may use it. */
const val MIN_VISIBLE_FRAMES = 3

/** Exponential smoothing weight of the newest sample (1 = no smoothing). */
const val SMOOTHING_ALPHA = 0.6f

/** Baseline = mean over the first reps of the set (the user's own "normal"; MediaPipe absolute angles are biased). */
const val BASELINE_REPS = 2
const val PLANK_BASELINE_MILLIS = 3000L

/**
 * Per-frame measures are compared with the baseline of the SAME rep-progress bin, because most measures
 * (trunk tilt, knee offset) legitimately change through a rep.
 */
const val PHASE_BINS = 10

/** A per-frame fault is reported after this many consecutive violating frames... */
const val FAULT_MIN_FRAMES = 4

/** ...or when this fraction of the rep's evaluated frames violate. */
const val FAULT_MIN_FRACTION = 0.4f

/** A fault is judged for a rep only if its landmarks were usable on this fraction of the rep's frames. */
const val MIN_COVERAGE = 0.8f

/** Frame gaps longer than this do not add to the plank hold time. */
const val MAX_HOLD_GAP_MILLIS = 500L

const val SETUP_HINT =
    "Position the phone ~1.5-2.5 m away at about 30-45 degrees in front of you so your whole body is visible"
const val FORM_DISCLAIMER =
    "Movement cues from your phone camera are approximate, not medical advice; stop if you feel pain"

enum class View { FRONT, SIDE, DIAGONAL_45, ANY }

/** What is measured. a/b/c are LEFT-side landmark indices; the right side is index+1. */
enum class Kind {
    /** Angle at b between a-b-c, degrees. */
    ANGLE,

    /** Inclination of segment a->b from vertical, degrees (0..90, unsigned). */
    TILT,

    /** (b.x - a.x) / scale. */
    DX,

    /** (b.y - a.y) / scale (y grows downward). */
    DY,

    /** a.y / scale. Only meaningful as a left-right difference. */
    Y,

    /** Knee (b) offset towards the body midline from the hip(a)-ankle(c) line, / scale. Negative = outward. */
    INWARD,

    /** Signed body-line deviation at b of a-b-c, degrees: positive = b below the a-c line (sag), negative = above (pike). */
    LINE,
}

enum class Scale { NONE, TORSO, LEG, SHOULDER_WIDTH, UPPER_ARM }

/**
 * Which body side a measure uses.
 * BEST = side with the better-seen landmarks, FRONT = leg with the lower ankle (lunge front leg),
 * HIGHER = leg with the higher ankle (step-up working leg, on the step),
 * EACH = both sides tracked independently, DIFF = left minus right (asymmetry).
 */
enum class Sides { BEST, FRONT, HIGHER, EACH, DIFF }

enum class Stat { PER_FRAME, REP_MAX, REP_MIN }

/** Which sign of (value - baseline) is bad. */
enum class Dir { ABOVE, BELOW, ABS }

class Measure(
    val kind: Kind,
    val a: Int,
    val b: Int = -1,
    val c: Int = -1,
    val sides: Sides = Sides.BEST,
    val scale: Scale = Scale.NONE,
)

/**
 * One checkable fault. [threshold] is a CHANGE FROM THE USER'S BASELINE in the measure's unit
 * (degrees, or fractions of [Measure.scale]); [stat] says whether it is judged per frame or on the
 * rep's max/min. [source]/[verified] are copied from data/form/form_rules.json.
 */
class Fault(
    val id: String,
    val cue: String,
    val view: View,
    val measure: Measure,
    val stat: Stat,
    val dir: Dir,
    val threshold: Float,
    /** Plank: the hold timer pauses while this fault is confirmed. */
    val holdGate: Boolean = false,
    val verified: Boolean = false,
    val source: String? = null,
)

/**
 * A lift declared as data. [repJoint] null = isometric hold. Rep bands are RAW joint angles in the
 * order the motion visits them: leave rest, reach the far end, start coming back, back at rest.
 * (Absolute defaults: baseline needs reps first, so bands only drive counting; faults are baseline-relative.)
 */
class LiftConfig(
    override val type: ExerciseType,
    val catalogSlug: String,
    val repJoint: Measure?,
    val restHigh: Boolean,
    val leaveRest: Float,
    val peak: Float,
    startReturn: Float,
    backAtRest: Float,
    val faults: List<Fault>,
    startHint: String,
) : ExerciseConfig {
    override val guidance = "$SETUP_HINT\n$startHint"

    /** RepCounter works in "rest = high angle" space, so rest-low joints are mirrored (180 - angle). */
    override val repRules = RepRules(norm(leaveRest), norm(peak), norm(startReturn), norm(backAtRest))

    fun norm(raw: Float) = if (restHigh) raw else 180f - raw

    /**
     * Rep-progress bin of a raw joint angle. The bins span the leave-rest..peak distance extended by one
     * such distance on each side, so resting and over-travel angles still land in sensible bins.
     */
    fun phaseBin(raw: Float): Int {
        val span = peak - leaveRest
        return (((raw - (leaveRest - span)) / (3f * span)) * PHASE_BINS).toInt().coerceIn(0, PHASE_BINS - 1)
    }

    private val stub = LiftAnalysis(type)
    override fun analyze(landmarks: List<PosePoint>, imageWidth: Int, imageHeight: Int): ExerciseAnalysis = stub

    override fun isCleanDepth(analysis: ExerciseAnalysis) = false
    override fun rejectionReasons(evidence: RepCycleEvidence) = emptyList<String>()
}

class FaultReport(
    val id: String,
    val cue: String,
    /** Landmark indices to highlight on PoseOverlay. */
    val landmarks: List<Int>,
    val view: View,
    val verified: Boolean,
    val source: String?,
)

class RepResult(
    val repIndex: Int,
    /** First [BASELINE_REPS] reps only teach the baseline and are never judged. */
    val calibration: Boolean,
    val judged: Boolean,
    val faults: List<FaultReport>,
    /** Body parts that were not seen well enough, e.g. "knees". */
    val cannotSee: List<String>,
) {
    val clean get() = judged && faults.isEmpty()
}

/** Set<Int> over a fixed bitmap so highlighting a frame allocates nothing. */
class LandmarkMask : AbstractSet<Int>() {
    private val on = BooleanArray(33)
    private var count = 0
    fun clear() {
        on.fill(false)
        count = 0
    }

    fun add(index: Int) {
        if (index in on.indices && !on[index]) {
            on[index] = true
            count++
        }
    }

    override val size get() = count
    override fun contains(element: Int) = element in on.indices && on[element]
    override fun iterator(): Iterator<Int> = on.indices.filter { on[it] }.iterator()
}

/** Reused every frame by [LiftSession]; PoseOverlay colours [activeLandmarks] with [state]. */
class LiftAnalysis(override val exerciseType: ExerciseType) : ExerciseAnalysis {
    override var state = FormState.GREY
    override var movementAngleDegrees: Float? = null
    override val bodyDeviationDegrees: Float? = null
    override val maxKneeValgusOffset: Float? = null
    val mask = LandmarkMask()
    override val activeLandmarks: Set<Int> get() = mask
    override val activeConnections: Set<Pair<Int, Int>> = emptySet()

    /** Plain-language line for the screen: "Cannot see your knees", a cue, or calibration progress. */
    var notice: String? = null
    var reps = 0
    var holdSeconds = 0
    var lastRep: RepResult? = null
}
