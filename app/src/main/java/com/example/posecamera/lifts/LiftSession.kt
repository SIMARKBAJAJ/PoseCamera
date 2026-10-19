package com.example.posecamera.lifts

import com.example.posecamera.pose.FormState
import com.example.posecamera.pose.PoseFrame
import com.example.posecamera.pose.RepCounter
import com.example.posecamera.pose.RepMovementState
import com.example.posecamera.pose.RepSetSummary
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

private const val LANDMARKS = 33
private const val EPS_PIXELS = 1f

private val CALIBRATING = arrayOf(
    "Learning your normal movement: do rep 1 of 2",
    "Learning your normal movement: do rep 2 of 2",
)
private const val BASELINE_LEARNED = "Normal movement learned; now checking your form"
private const val REPS_ONLY = "Counting only: form is not judged for this lift"
private const val PLANK_CALIBRATING = "Hold steady: learning your normal plank"

internal fun partName(index: Int): String = when (index) {
    7, 8 -> "ears"
    11, 12 -> "shoulders"
    13, 14 -> "elbows"
    15, 16 -> "wrists"
    23, 24 -> "hips"
    25, 26 -> "knees"
    27, 28 -> "ankles"
    29, 30 -> "heels"
    31, 32 -> "feet"
    else -> "body"
}

/** One thing measured each frame: the rep joint (fault == null) or one fault (one side for Sides.EACH). */
private class Probe(val fault: Fault?, val measure: Measure, val side: Int) {
    var curSide = 0
    var missIdx = -1
    var missPart: String? = null
    var seenNow = false
    var everSeen = false
    val baseSum = FloatArray(PHASE_BINS)
    val baseN = IntArray(PHASE_BINS)
    var baseTotal = 0
    val repSum = FloatArray(PHASE_BINS)
    val repNBin = IntArray(PHASE_BINS)
    var repN = 0
    var extreme = 0f
    var viol = 0
    var run = 0
    var maxRun = 0
    var hlSide = 0
    var confirmedEver = false

    fun startRep() {
        repSum.fill(0f); repNBin.fill(0); repN = 0; extreme = 0f; viol = 0; run = 0; maxRun = 0; hlSide = 0
        missPart = null; seenNow = false
    }

    fun reset() {
        startRep()
        baseSum.fill(0f); baseN.fill(0); baseTotal = 0; everSeen = false; confirmedEver = false
    }
}

/**
 * Per-set engine for one [LiftConfig]. Feed it every [PoseFrame] on the main thread; it returns the
 * reused [LiftAnalysis] for PoseOverlay. onFrame code allocates nothing except what [RepCounter] already
 * does (a boxed angle and a RepSetProgress). Allocation happens only at rep end.
 *
 * Safety: a measure is only evaluated when every landmark it needs has had visibility >= [MIN_VISIBILITY]
 * for [MIN_VISIBLE_FRAMES] consecutive frames; otherwise the notice says "Cannot see your <part>".
 */
class LiftSession(private val config: LiftConfig) {
    private val sx = FloatArray(LANDMARKS)
    private val sy = FloatArray(LANDMARKS)
    private val vis = FloatArray(LANDMARKS)
    private val run = IntArray(LANDMARKS)
    private var w = 1f
    private var h = 1f

    val analysis = LiftAnalysis(config.type)
    private val counter = RepCounter(config)
    private val joint = config.repJoint?.let { Probe(null, it, -1) }
    private val probes: Array<Probe> = buildList {
        for (f in config.faults) {
            if (f.measure.sides == Sides.EACH) {
                add(Probe(f, f.measure, 0))
                add(Probe(f, f.measure, 1))
            } else {
                add(Probe(f, f.measure, -1))
            }
        }
    }.toTypedArray()

    private val results = ArrayList<RepResult>()
    private var finished = 0
    private var wasActive = false
    private var repFrames = 0
    private var bin = 0
    private var restState = FormState.GREEN
    private var restNotice: String? = null
    private var restLandmarks = IntArray(0)

    private var started = false
    private var startMillis = 0L
    private var lastMillis = 0L
    private var holdMillis = 0L

    private var lastPart: String? = null
    private var lastPartText = ""

    fun onFrame(frame: PoseFrame): LiftAnalysis {
        smooth(frame)
        w = frame.imageWidth.toFloat().coerceAtLeast(1f)
        h = frame.imageHeight.toFloat().coerceAtLeast(1f)
        if (joint == null) hold(frame.timestampMillis) else reps(frame.timestampMillis, joint)
        return analysis
    }

    // ---- landmarks -------------------------------------------------------------------------------

    private fun smooth(frame: PoseFrame) {
        val landmarks = frame.landmarks
        for (i in 0 until LANDMARKS) {
            val p = if (i < landmarks.size) landmarks[i] else null
            if (p != null && p.x.isFinite() && p.y.isFinite() &&
                p.visibility.isFinite() && p.visibility >= MIN_VISIBILITY
            ) {
                if (run[i] == 0) {
                    sx[i] = p.x
                    sy[i] = p.y
                } else {
                    sx[i] += SMOOTHING_ALPHA * (p.x - sx[i])
                    sy[i] += SMOOTHING_ALPHA * (p.y - sy[i])
                }
                vis[i] = p.visibility
                if (run[i] < MIN_VISIBLE_FRAMES) run[i]++
            } else {
                run[i] = 0
            }
        }
    }

    private fun usable(p: Probe, index: Int): Boolean {
        if (run[index] >= MIN_VISIBLE_FRAMES) return true
        p.missIdx = index
        return false
    }

    private fun usableSide(p: Probe, m: Measure, s: Int): Boolean {
        if (!usable(p, m.a + s)) return false
        if (m.b >= 0 && !usable(p, m.b + s)) return false
        if (m.c >= 0 && !usable(p, m.c + s)) return false
        if (m.kind == Kind.INWARD && !usable(p, partner(m.a + s))) return false
        return when (m.scale) {
            Scale.NONE -> true
            Scale.TORSO -> usable(p, SHOULDER + s) && usable(p, HIP + s)
            Scale.LEG -> usable(p, HIP + s) && usable(p, ANKLE + s)
            Scale.UPPER_ARM -> usable(p, SHOULDER + s) && usable(p, ELBOW + s)
            Scale.SHOULDER_WIDTH -> usable(p, SHOULDER) && usable(p, SHOULDER + 1)
        }
    }

    private fun minVis(m: Measure, s: Int): Float {
        var v = vis[m.a + s]
        if (m.b >= 0) v = minOf(v, vis[m.b + s])
        if (m.c >= 0) v = minOf(v, vis[m.c + s])
        return v
    }

    /** The measured value, or NaN when a needed landmark is not reliably visible (p.missIdx says which). */
    private fun value(p: Probe): Float {
        val m = p.measure
        p.missIdx = -1
        return when (m.sides) {
            Sides.EACH -> evalIfUsable(p, m, p.side)
            Sides.DIFF -> {
                p.curSide = 2
                if (usableSide(p, m, 0) && usableSide(p, m, 1)) evalSide(m, 0) - evalSide(m, 1) else Float.NaN
            }
            Sides.BEST -> {
                val left = usableSide(p, m, 0)
                val leftMiss = p.missIdx
                val right = usableSide(p, m, 1)
                when {
                    left && right -> evalIfUsable(p, m, if (minVis(m, 0) >= minVis(m, 1)) 0 else 1)
                    left -> evalIfUsable(p, m, 0)
                    right -> evalIfUsable(p, m, 1)
                    else -> {
                        p.missIdx = leftMiss
                        Float.NaN
                    }
                }
            }
            // ponytail: the lower ankle (larger y) is the front foot per form_rules.json; can flip near
            // standing, which is harmless there because only in-rep frames are used.
            // HIGHER = the opposite leg (step-up working leg stands on the step, so its ankle is higher).
            Sides.FRONT, Sides.HIGHER -> if (usable(p, ANKLE) && usable(p, ANKLE + 1)) {
                val rightLower = sy[ANKLE + 1] > sy[ANKLE]
                evalIfUsable(p, m, if (rightLower == (m.sides == Sides.FRONT)) 1 else 0)
            } else {
                Float.NaN
            }
        }
    }

    private fun evalIfUsable(p: Probe, m: Measure, s: Int): Float {
        if (!usableSide(p, m, s)) return Float.NaN
        p.curSide = s
        return evalSide(m, s)
    }

    private fun scaleOf(m: Measure, s: Int): Float = when (m.scale) {
        Scale.NONE -> 1f
        Scale.TORSO -> dist(SHOULDER + s, HIP + s)
        Scale.LEG -> dist(HIP + s, ANKLE + s)
        Scale.UPPER_ARM -> dist(SHOULDER + s, ELBOW + s)
        Scale.SHOULDER_WIDTH -> dist(SHOULDER, SHOULDER + 1)
    }

    private fun dist(i: Int, j: Int) = hypot((sx[i] - sx[j]) * w, (sy[i] - sy[j]) * h)

    private fun evalSide(m: Measure, s: Int): Float {
        val a = m.a + s
        val ax = sx[a] * w
        val ay = sy[a] * h
        val bx = if (m.b >= 0) sx[m.b + s] * w else 0f
        val by = if (m.b >= 0) sy[m.b + s] * h else 0f
        val cx = if (m.c >= 0) sx[m.c + s] * w else 0f
        val cy = if (m.c >= 0) sy[m.c + s] * h else 0f
        val scale = scaleOf(m, s)
        if (!(scale >= EPS_PIXELS)) return Float.NaN
        return when (m.kind) {
            Kind.ANGLE -> angleAt(ax, ay, bx, by, cx, cy)
            Kind.TILT -> {
                val dx = bx - ax
                val dy = by - ay
                if (dx == 0f && dy == 0f) Float.NaN else Math.toDegrees(atan2(abs(dx), abs(dy)).toDouble()).toFloat()
            }
            Kind.DX -> (bx - ax) / scale
            Kind.DY -> (by - ay) / scale
            Kind.Y -> ay / scale
            Kind.INWARD -> {
                val dyLine = cy - ay
                val towardMid = sx[partner(a)] * w - ax
                if (abs(dyLine) < EPS_PIXELS || towardMid == 0f) {
                    Float.NaN
                } else {
                    val lineX = ax + (cx - ax) * (by - ay) / dyLine
                    (bx - lineX) * (if (towardMid > 0f) 1f else -1f) / scale
                }
            }
            Kind.LINE -> {
                val angle = angleAt(ax, ay, bx, by, cx, cy)
                val dx = cx - ax
                if (angle.isNaN() || abs(dx) < EPS_PIXELS) {
                    Float.NaN
                } else {
                    val lineY = ay + (cy - ay) * (bx - ax) / dx
                    val deviation = 180f - angle
                    if (by - lineY >= 0f) deviation else -deviation
                }
            }
        }
    }

    private fun angleAt(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
        val ux = ax - bx
        val uy = ay - by
        val vx = cx - bx
        val vy = cy - by
        val lu = hypot(ux, uy)
        val lv = hypot(vx, vy)
        if (lu == 0f || lv == 0f) return Float.NaN
        val cos = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    private fun partner(index: Int) = if (index % 2 == 1) index + 1 else index - 1

    private fun breach(f: Fault, change: Float) = when (f.dir) {
        Dir.ABOVE -> change >= f.threshold
        Dir.BELOW -> -change >= f.threshold
        Dir.ABS -> abs(change) >= f.threshold
    }

    private fun base(p: Probe, bin: Int) = p.baseSum[bin] / p.baseN[bin]

    // ---- rep-based lifts -------------------------------------------------------------------------

    private fun reps(t: Long, j: Probe) {
        val v = value(j)
        val unseen = v.isNaN()
        bin = if (unseen) -1 else config.phaseBin(v)
        analysis.movementAngleDegrees = if (unseen) null else config.norm(v)
        val progress = counter.onFrame(analysis, t)
        val active = progress.movementState != RepMovementState.STANDING
        analysis.reps = progress.attemptedReps
        if (active && !wasActive) startRep()
        if (active) accumulate()
        if (progress.attemptedReps > finished) {
            finishRep()
            finished = progress.attemptedReps
        }
        wasActive = active
        showReps(j, unseen, active)
    }

    private fun startRep() {
        repFrames = 0
        for (i in probes.indices) probes[i].startRep()
    }

    private fun accumulate() {
        repFrames++
        for (i in probes.indices) {
            val p = probes[i]
            val f = p.fault!!
            val v = if (bin < 0) Float.NaN else value(p)
            p.seenNow = !v.isNaN()
            if (!p.seenNow) {
                p.missPart = partName(if (bin < 0) joint!!.missIdx else p.missIdx)
                p.run = 0
                continue
            }
            p.everSeen = true
            p.repN++
            when (f.stat) {
                Stat.PER_FRAME -> {
                    p.repSum[bin] += v
                    p.repNBin[bin]++
                    if (finished >= BASELINE_REPS && p.baseN[bin] > 0) {
                        if (breach(f, v - base(p, bin))) {
                            p.viol++
                            p.run++
                            if (p.run > p.maxRun) p.maxRun = p.run
                            p.hlSide = p.curSide
                        } else {
                            p.run = 0
                        }
                    }
                }
                Stat.REP_MAX -> if (p.repN == 1 || v > p.extreme) {
                    p.extreme = v
                    p.hlSide = p.curSide
                }
                Stat.REP_MIN -> if (p.repN == 1 || v < p.extreme) {
                    p.extreme = v
                    p.hlSide = p.curSide
                }
            }
        }
    }

    private fun finishRep() {
        val calibration = finished < BASELINE_REPS
        val faults = ArrayList<FaultReport>()
        val unseen = ArrayList<String>()
        var judged = false
        for (p in probes) {
            val f = p.fault!!
            val covered = p.repN > 0 && p.repN >= MIN_COVERAGE * repFrames
            if (!covered || (!calibration && p.baseTotal == 0)) {
                val part = p.missPart ?: "body"
                if (part !in unseen) unseen += part
                continue
            }
            if (calibration) {
                if (f.stat == Stat.PER_FRAME) {
                    for (b in 0 until PHASE_BINS) {
                        p.baseSum[b] += p.repSum[b]
                        p.baseN[b] += p.repNBin[b]
                    }
                    p.baseTotal += p.repN
                } else {
                    p.baseSum[0] += p.extreme
                    p.baseN[0]++
                    p.baseTotal++
                }
                continue
            }
            judged = true
            val bad = if (f.stat == Stat.PER_FRAME) {
                p.maxRun >= FAULT_MIN_FRAMES || p.viol >= FAULT_MIN_FRACTION * p.repN
            } else {
                breach(f, p.extreme - base(p, 0))
            }
            if (bad) addFault(faults, f, p.measure, p.hlSide)
        }
        val result = RepResult(finished, calibration, judged, faults, unseen)
        results += result
        analysis.lastRep = result
        restLandmarks = faults.flatMap { it.landmarks }.distinct().toIntArray()
        when {
            calibration -> {
                restState = FormState.GREEN
                restNotice = BASELINE_LEARNED
            }
            faults.isNotEmpty() -> {
                restState = FormState.RED
                restNotice = faults[0].cue
            }
            judged -> {
                restState = FormState.GREEN
                restNotice = if (unseen.isEmpty()) null else "Cannot see your ${unseen[0]}"
            }
            else -> {
                restState = FormState.GREY
                restNotice = "Cannot see your ${unseen.firstOrNull() ?: "body"}"
            }
        }
    }

    private fun addFault(into: ArrayList<FaultReport>, f: Fault, m: Measure, side: Int) {
        val indices = ArrayList<Int>(6)
        for (s in 0..1) {
            if (side == 2 || side == s) {
                indices += m.a + s
                if (m.b >= 0) indices += m.b + s
                if (m.c >= 0) indices += m.c + s
            }
        }
        val existing = into.indexOfFirst { it.id == f.id }
        val merged = if (existing >= 0) (into.removeAt(existing).landmarks + indices).distinct() else indices
        into += FaultReport(f.id, f.cue, merged, f.view, f.verified, f.source)
    }

    private fun showReps(j: Probe, unseen: Boolean, active: Boolean) {
        val mask = analysis.mask
        mask.clear()
        var state = FormState.GREEN
        var notice: String? = null
        when {
            probes.isEmpty() -> {
                state = FormState.GREY
                notice = REPS_ONLY
            }
            unseen -> {
                state = FormState.GREY
                notice = cannotSeeText(j.missIdx)
            }
            finished < BASELINE_REPS -> {
                state = FormState.GREY
                notice = CALIBRATING[finished]
            }
            active -> {
                var missing = -1
                for (i in probes.indices) {
                    val p = probes[i]
                    if (!p.seenNow) {
                        missing = p.missIdx
                    } else if (p.fault!!.stat == Stat.PER_FRAME && p.run > 0) {
                        mark(p.measure, p.curSide)
                        if (p.run >= FAULT_MIN_FRAMES) {
                            state = FormState.RED
                            if (notice == null) notice = p.fault.cue
                        } else if (state != FormState.RED) {
                            state = FormState.YELLOW
                        }
                    }
                }
                if (notice == null && missing >= 0) notice = cannotSeeText(missing)
            }
            else -> {
                state = restState
                notice = restNotice
                for (i in restLandmarks.indices) mask.add(restLandmarks[i])
            }
        }
        analysis.state = state
        analysis.notice = notice
    }

    private fun mark(m: Measure, side: Int) {
        for (s in 0..1) {
            if (side == 2 || side == s) {
                analysis.mask.add(m.a + s)
                if (m.b >= 0) analysis.mask.add(m.b + s)
                if (m.c >= 0) analysis.mask.add(m.c + s)
            }
        }
    }

    private fun cannotSeeText(index: Int): String {
        val part = partName(index)
        if (part !== lastPart) {
            lastPart = part
            lastPartText = "Cannot see your $part"
        }
        return lastPartText
    }

    // ---- plank (isometric) -----------------------------------------------------------------------

    private fun hold(t: Long) {
        val dt = if (lastMillis == 0L) 0L else (t - lastMillis).coerceIn(0L, MAX_HOLD_GAP_MILLIS)
        lastMillis = t
        val baselineDone = started && t - startMillis >= PLANK_BASELINE_MILLIS
        analysis.mask.clear()
        var gate = true
        var state = FormState.GREEN
        var notice: String? = null
        var missing = -1
        var anySeen = false
        for (i in probes.indices) {
            val p = probes[i]
            val f = p.fault!!
            val v = value(p)
            p.seenNow = !v.isNaN()
            if (!p.seenNow) {
                p.run = 0
                missing = p.missIdx
                if (f.holdGate) gate = false
                continue
            }
            anySeen = true
            p.everSeen = true
            if (!started) {
                started = true
                startMillis = t
            }
            if (!baselineDone) {
                p.baseSum[0] += v
                p.baseN[0]++
                p.baseTotal++
            } else if (p.baseTotal > 0) {
                if (breach(f, v - base(p, 0))) p.run++ else p.run = 0
                if (p.run > 0) {
                    mark(p.measure, p.curSide)
                    if (p.run >= FAULT_MIN_FRAMES) {
                        state = FormState.RED
                        if (notice == null) notice = f.cue
                        p.confirmedEver = true
                        if (f.holdGate) gate = false
                    } else if (state != FormState.RED) {
                        state = FormState.YELLOW
                    }
                }
            }
        }
        if (started && gate) holdMillis += dt
        analysis.holdSeconds = (holdMillis / 1000L).toInt()
        when {
            !anySeen -> {
                state = FormState.GREY
                notice = cannotSeeText(missing)
            }
            !baselineDone -> {
                state = FormState.GREY
                notice = PLANK_CALIBRATING
            }
            notice == null && missing >= 0 -> notice = cannotSeeText(missing)
        }
        analysis.state = state
        analysis.notice = notice
    }

    // ---- summary ---------------------------------------------------------------------------------

    /** Builds the set summary and resets for the next set (a new set learns a new baseline). */
    fun endSet(): RepSetSummary {
        val total = counter.endSet().attemptedReps
        val cues = ArrayList<String>()
        val unseen = ArrayList<String>()
        var judged = 0
        var clean = 0
        if (joint == null) {
            for (p in probes) {
                val f = p.fault!!
                if (p.confirmedEver && f.cue !in cues) cues += f.cue
                if (!p.everSeen && partName(p.measure.a) !in unseen) unseen += partName(p.measure.a)
            }
        } else {
            for (r in results) {
                if (r.judged) {
                    judged++
                    if (r.faults.isEmpty()) clean++
                }
                for (f in r.faults) cues += f.cue
                for (part in r.cannotSee) if (part !in unseen) unseen += part
            }
        }
        val summary = RepSetSummary(
            exerciseType = config.type,
            attemptedReps = judged,
            cleanReps = clean,
            rejectedReps = judged - clean,
            formScore = if (judged == 0) 0f else clean * 100f / judged,
            mostCommonRejectionReason = cues.groupingBy { it }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })?.key,
            rejectionReasons = cues,
            totalReps = total,
            holdSeconds = if (joint == null) holdMillis / 1000f else null,
            cannotSee = unseen,
            catalogSlug = config.catalogSlug,
        )
        reset()
        return summary
    }

    private fun reset() {
        run.fill(0)
        for (i in probes.indices) probes[i].reset()
        joint?.reset()
        results.clear()
        finished = 0
        wasActive = false
        repFrames = 0
        started = false
        startMillis = 0L
        lastMillis = 0L
        holdMillis = 0L
        restState = FormState.GREEN
        restNotice = null
        restLandmarks = IntArray(0)
        analysis.mask.clear()
        analysis.state = FormState.GREY
        analysis.notice = null
        analysis.reps = 0
        analysis.holdSeconds = 0
        analysis.lastRep = null
        analysis.movementAngleDegrees = null
    }

    private companion object {
        const val SHOULDER = 11
        const val ELBOW = 13
        const val HIP = 23
        const val ANKLE = 27
    }
}
