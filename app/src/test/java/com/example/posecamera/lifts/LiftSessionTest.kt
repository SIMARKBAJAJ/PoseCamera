package com.example.posecamera.lifts

import com.example.posecamera.network.toSubmitLiftSetRequest
import com.example.posecamera.pose.FormState
import com.example.posecamera.pose.PoseFrame
import com.example.posecamera.pose.PosePoint
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic skeletons, 1000x1000 image, no noise. Rep lifts drive one joint angle through
 * rest -> peak -> rest; each fault has a pose variation that crosses its threshold.
 */
class LiftSessionTest {
    private var t = 1000L

    // ---- synthetic figure ------------------------------------------------------------------------

    private class Fig {
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        fun set(i: Int, px: Float, py: Float) {
            x[i] = px
            y[i] = py
        }

        /** Places c at distance len from b, at `sign*deg` from the direction b->a (so angle a-b-c == deg). */
        fun rot3(a: Int, b: Int, c: Int, deg: Float, len: Float, sign: Float) {
            var ux = x[a] - x[b]
            var uy = y[a] - y[b]
            val l = hypot(ux, uy)
            ux /= l
            uy /= l
            val r = Math.toRadians((sign * deg).toDouble())
            val cs = cos(r).toFloat()
            val sn = sin(r).toFloat()
            set(c, x[b] + len * (ux * cs - uy * sn), y[b] + len * (ux * sn + uy * cs))
        }
    }

    /** Front-facing standing figure, left landmarks (odd index) at larger x. */
    private fun base(): Fig {
        val f = Fig()
        mapOf(
            7 to (0.56f to 0.12f), 8 to (0.44f to 0.12f),
            11 to (0.58f to 0.2f), 12 to (0.42f to 0.2f),
            13 to (0.58f to 0.35f), 14 to (0.42f to 0.35f),
            15 to (0.58f to 0.5f), 16 to (0.42f to 0.5f),
            23 to (0.55f to 0.5f), 24 to (0.45f to 0.5f),
            25 to (0.55f to 0.7f), 26 to (0.45f to 0.7f),
            27 to (0.55f to 0.9f), 28 to (0.45f to 0.9f),
            29 to (0.55f to 0.93f), 30 to (0.45f to 0.93f),
            31 to (0.58f to 0.93f), 32 to (0.42f to 0.93f),
        ).forEach { (i, p) -> f.set(i, p.first, p.second) }
        return f
    }

    private fun frame(f: Fig, config: LiftConfig, hide: Set<Int> = emptySet()): PoseFrame {
        t += 100L
        return PoseFrame(
            landmarks = List(33) { PosePoint(f.x[it], f.y[it], if (it in hide) 0.2f else 1f) },
            imageWidth = 1000,
            imageHeight = 1000,
            fps = 10f,
            mirrorHorizontally = false,
            analysis = config.analyze(emptyList(), 1000, 1000),
            timestampMillis = t,
        )
    }

    private fun bendLegs(f: Fig, angle: Float, kneeDx: (Int) -> Float = { 0f }) {
        for (s in 0..1) {
            f.set(25 + s, f.x[23 + s] + kneeDx(s), 0.7f)
            f.rot3(23 + s, 25 + s, 27 + s, angle, 0.2f, if (s == 0) 1f else -1f)
            f.set(29 + s, f.x[27 + s], f.y[27 + s] + 0.03f)
            f.set(31 + s, f.x[27 + s] + 0.03f, f.y[27 + s] + 0.03f)
        }
    }

    private fun bendArms(f: Fig, angle: Float, rightAngle: Float = angle) {
        f.rot3(11, 13, 15, angle, 0.15f, 1f)
        f.rot3(12, 14, 16, rightAngle, 0.15f, -1f)
    }

    // ---- rigs ------------------------------------------------------------------------------------

    private class Rig(
        val config: LiftConfig,
        val rest: Float,
        val peak: Float,
        val build: (angle: Float, fault: String?, atPeak: Boolean) -> Fig,
    )

    private class Case(val id: String, val expected: Set<Int>, val peak: Float? = null)

    private fun lunge(angle: Float, fault: String?, atPeak: Boolean): Fig {
        val f = base()
        val lean = when (fault) {
            "excess_trunk_lean" -> 0.15f
            "small_lean" -> 0.05f
            else -> 0f
        }
        f.set(11, 0.58f + lean, 0.2f)
        f.set(12, 0.42f + lean, 0.2f)
        bendLegs(f, angle)
        if (fault == "heel_lift_front_foot") f.y[29] -= 0.08f
        if (fault == "wide_balance_sway" && atPeak) {
            for (i in intArrayOf(11, 12, 23, 24, 25, 26)) f.x[i] += 0.12f // hips drift, feet stay
        }
        return f
    }

    private fun overheadPress(angle: Float, fault: String?, atPeak: Boolean): Fig {
        val f = base()
        val lean = if (fault == "lumbar_lean_back") 0.15f else 0f
        val shrug = if (fault == "shoulder_shrug_elevation") -0.04f else 0f
        for (s in 0..1) {
            val sx = (if (s == 0) 0.58f else 0.42f) + lean
            val sy = 0.2f + shrug
            f.set(11 + s, sx, sy)
            f.set(7 + s, f.x[7 + s] + lean, f.y[7 + s])
            f.set(13 + s, sx, sy + 0.15f + if (fault == "asymmetry" && s == 0) 0.08f else 0f)
        }
        bendArms(f, angle)
        return f
    }

    private fun curl(angle: Float, fault: String?, atPeak: Boolean): Fig {
        val f = base()
        val swing = if (fault == "torso_swing") 0.08f else 0f
        for (s in 0..1) {
            val sx = (if (s == 0) 0.58f else 0.42f) + swing
            f.set(11 + s, sx, 0.2f)
            f.set(13 + s, sx + if (fault == "elbow_drift_forward" && s == 0) 0.08f else 0f, 0.35f)
        }
        bendArms(f, angle, if (fault == "asymmetry") minOf(angle + 30f, 179f) else angle)
        return f
    }

    /** Lying side view: shoulder-hip-knee angle drives the rep; hips differ in y so Y-asymmetry has a scale. */
    private fun bridge(angle: Float, fault: String?, atPeak: Boolean): Fig {
        val f = Fig()
        f.set(11, 0.30f, 0.60f); f.set(12, 0.30f, 0.70f)
        f.set(23, 0.55f, 0.60f); f.set(24, 0.45f, 0.70f)
        for (s in 0..1) {
            f.rot3(11 + s, 23 + s, 25 + s, angle, 0.25f, 1f)
            f.set(27 + s, f.x[25 + s], f.y[25 + s] + 0.25f)
        }
        if (fault == "pelvic_asymmetry") for (i in intArrayOf(24, 26, 28)) f.y[i] += 0.1f
        return f
    }

    // ---- batch 2 figures (reuse the batch-1 builders; fault ids are mapped onto the ones they know) ----

    private fun squat(angle: Float, fault: String?, atPeak: Boolean) =
        lunge(angle, if (fault == "weight_drifts_forward_trunk_lean") "excess_trunk_lean" else fault, atPeak)

    /** Arm lifts (pull-up, pulldown, pushdown, face pull) on the curl figure: swing = trunk lean, drift = elbow offset. */
    private fun armLift(angle: Float, fault: String?, atPeak: Boolean) = curl(
        angle,
        when (fault) {
            "kipping_swing", "trunk_lean_back_momentum", "torso_lean_momentum", "trunk_lean_back" -> "torso_swing"
            "elbow_flare_drift", "elbows_below_wrists" -> "elbow_drift_forward"
            else -> fault
        },
        atPeak,
    )

    /** Front view, angle = shoulder abduction (hip-shoulder-elbow); arms swing outwards. */
    private fun lateralRaise(angle: Float, fault: String?, atPeak: Boolean): Fig {
        val f = base()
        val swing = if (fault == "torso_swing") 0.08f else 0f
        val shrug = if (fault == "shrug_trap_dominance") -0.04f else 0f
        for (s in 0..1) f.set(11 + s, (if (s == 0) 0.58f else 0.42f) + swing, 0.2f + shrug)
        for (s in 0..1) f.rot3(23 + s, 11 + s, 13 + s, angle, 0.15f, if (s == 0) -1f else 1f)
        return f
    }

    /** Lying side view (the bridge figure) plus feet; the knee angle drives the rep. */
    private fun crunch(angle: Float, fault: String?, atPeak: Boolean): Fig {
        val f = bridge(angle, null, atPeak)
        for (s in 0..1) {
            f.set(29 + s, f.x[27 + s], f.y[27 + s] + 0.03f)
            f.set(31 + s, f.x[27 + s] + 0.03f, f.y[27 + s] + 0.03f)
        }
        if (fault == "feet_lift") f.y[29] -= 0.08f
        return f
    }

    /** Right leg works (bent, ankle on the step = higher); left leg stays almost straight on the floor. */
    private fun stepUp(angle: Float, fault: String?, atPeak: Boolean): Fig {
        val f = squat(angle, if (fault == "excess_trunk_lean") fault else null, atPeak)
        f.rot3(23, 25, 27, 178f, 0.2f, 1f)
        f.set(29, f.x[27], f.y[27] + 0.03f)
        f.set(31, f.x[27] + 0.03f, f.y[27] + 0.03f)
        return f
    }

    /** Ankle angle (knee-ankle-foot index) drives the rep. */
    private fun calf(angle: Float, fault: String?, atPeak: Boolean): Fig {
        val f = base()
        for (s in 0..1) f.rot3(25 + s, 27 + s, 31 + s, angle, 0.05f, if (s == 0) 1f else -1f)
        return f
    }

    /**
     * Valgus and splay are frontal-plane measures. A rig that bends the legs in the image plane would make
     * them swing with the rep, so these two faults are tested with the real fault list but an elbow driver
     * and upright static legs.
     */
    private fun frontalRig(source: LiftConfig, faultId: String, rightAnkleUp: Boolean = false): Rig {
        val driver = LiftConfig(
            type = source.type, catalogSlug = "x", repJoint = Measure(Kind.ANGLE, 11, 13, 15),
            restHigh = true, leaveRest = 150f, peak = 115f, startReturn = 130f, backAtRest = 160f,
            faults = source.faults, startHint = "",
        )
        return Rig(driver, 172f, 90f) { angle, fault, _ ->
            val f = base()
            for (s in 0..1) f.set(13 + s, f.x[11 + s], 0.35f)
            bendArms(f, angle)
            if (rightAnkleUp) f.y[28] -= 0.05f // step-up: the right foot is on the step (the working leg)
            if (fault == faultId) f.x[26] += 0.06f // right knee moves toward the midline (valgus / bridge: splay)
            f
        }
    }

    private val lungeRig = Rig(Lifts.lunge, 172f, 90f, ::lunge)
    private val pressRig = Rig(Lifts.overheadPress, 70f, 170f, ::overheadPress)
    private val curlRig = Rig(Lifts.bicepsCurl, 165f, 50f, ::curl)
    private val bridgeRig = Rig(Lifts.gluteBridge, 110f, 176f, ::bridge)
    private val gobletRig = Rig(Lifts.gobletSquat, 172f, 90f, ::squat)
    private val pullUpRig = Rig(Lifts.pullUp, 170f, 60f, ::armLift)
    private val pulldownRig = Rig(Lifts.latPulldown, 170f, 70f, ::armLift)
    private val pushdownRig = Rig(Lifts.tricepsPushdown, 70f, 170f, ::armLift)
    private val raiseRig = Rig(Lifts.lateralRaise, 10f, 85f, ::lateralRaise)
    private val crunchRig = Rig(Lifts.crunch, 145f, 90f, ::crunch)
    private val facePullRig = Rig(Lifts.facePull, 170f, 60f, ::armLift)
    private val stepRig = Rig(Lifts.stepUp, 105f, 172f, ::stepUp)

    private val cases = listOf(
        lungeRig to Case("excess_trunk_lean", setOf(23, 11)),
        lungeRig to Case("insufficient_depth", setOf(23, 25, 27), peak = 108f),
        lungeRig to Case("heel_lift_front_foot", setOf(29, 31)),
        lungeRig to Case("wide_balance_sway", setOf(27, 23)),
        frontalRig(Lifts.lunge, "front_knee_valgus") to Case("front_knee_valgus", setOf(24, 26, 28)),
        pressRig to Case("lumbar_lean_back", setOf(23, 11)),
        pressRig to Case("incomplete_lockout", setOf(11, 13, 15), peak = 150f),
        pressRig to Case("asymmetry", setOf(11, 15, 12, 16)),
        pressRig to Case("shoulder_shrug_elevation", setOf(7, 11, 8, 12)),
        curlRig to Case("torso_swing", setOf(23, 11)),
        curlRig to Case("elbow_drift_forward", setOf(23, 11, 13)),
        curlRig to Case("incomplete_rom", setOf(11, 13, 15), peak = 66f),
        curlRig to Case("asymmetry", setOf(11, 13, 15, 12, 14, 16)),
        bridgeRig to Case("incomplete_lockout", setOf(11, 23, 25), peak = 162f),
        bridgeRig to Case("pelvic_asymmetry", setOf(23, 24)),
        frontalRig(Lifts.gluteBridge, "knee_splay") to Case("knee_splay", setOf(24, 26, 28)),
        gobletRig to Case("weight_drifts_forward_trunk_lean", setOf(23, 11)),
        gobletRig to Case("insufficient_depth", setOf(23, 25, 27), peak = 108f),
        frontalRig(Lifts.gobletSquat, "knee_valgus") to Case("knee_valgus", setOf(24, 26, 28)),
        pullUpRig to Case("incomplete_rom_chin_below_bar", setOf(11, 13, 15), peak = 80f),
        pullUpRig to Case("kipping_swing", setOf(23, 11)),
        pullUpRig to Case("asymmetry", setOf(11, 13, 15, 12, 14, 16)),
        pulldownRig to Case("trunk_lean_back_momentum", setOf(23, 11)),
        pulldownRig to Case("incomplete_rom", setOf(11, 13, 15), peak = 90f),
        pushdownRig to Case("elbow_flare_drift", setOf(23, 11, 13)),
        pushdownRig to Case("torso_lean_momentum", setOf(23, 11)),
        raiseRig to Case("raise_above_shoulder", setOf(23, 11, 13), peak = 110f),
        raiseRig to Case("torso_swing", setOf(23, 11)),
        raiseRig to Case("shrug_trap_dominance", setOf(7, 11, 8, 12)),
        crunchRig to Case("partial_range", setOf(11, 23, 25), peak = 108f),
        crunchRig to Case("feet_lift", setOf(29, 31)),
        facePullRig to Case("elbows_below_wrists", setOf(13, 15)),
        facePullRig to Case("trunk_lean_back", setOf(23, 11)),
        stepRig to Case("excess_trunk_lean", setOf(23, 11)),
        stepRig to Case("incomplete_extension", setOf(24, 26, 28), peak = 155f),
        frontalRig(Lifts.stepUp, "knee_valgus", rightAnkleUp = true) to Case("knee_valgus", setOf(24, 26, 28)),
    )

    // ---- driving a session -----------------------------------------------------------------------

    private fun ramp(from: Float, to: Float, steps: Int) = List(steps) { from + (to - from) * (it + 1) / (steps + 1) }

    /** One rep; returns true if any frame said "Cannot see ...". */
    private fun rep(
        s: LiftSession,
        rig: Rig,
        fault: String? = null,
        peak: Float = rig.peak,
        hide: Set<Int> = emptySet(),
    ): Boolean {
        val angles = List(4) { rig.rest } + ramp(rig.rest, peak, 8) + List(6) { peak } +
            ramp(peak, rig.rest, 8) + List(5) { rig.rest }
        var sawCannotSee = false
        for (a in angles) {
            s.onFrame(frame(rig.build(a, fault, a == peak), rig.config, hide))
            if (s.analysis.notice?.startsWith("Cannot see") == true) sawCannotSee = true
        }
        return sawCannotSee
    }

    private fun baseline(s: LiftSession, rig: Rig, fault: String? = null, peak: Float = rig.peak) =
        repeat(BASELINE_REPS) { rep(s, rig, fault, peak) }

    private fun hideFor(rig: Rig, c: Case): Set<Int> {
        val both = c.expected.flatMap { listOf(it, if (it % 2 == 1) it + 1 else it - 1) }.toSet()
        val joint = rig.config.repJoint!!.let { m ->
            listOf(m.a, m.b, m.c).filter { it >= 0 }.flatMap { listOf(it, it + 1) }.toSet()
        }
        // keep the rep joint visible when the fault uses other landmarks, so the rep still completes
        return (both - joint).ifEmpty { both }
    }

    // ---- (a) clean reps are counted ----------------------------------------------------------------

    @Test
    fun cleanRepsAreCountedJudgedAndScoredAsClean() {
        for (rig in listOf(
            lungeRig, pressRig, curlRig, bridgeRig,
            gobletRig, pullUpRig, pulldownRig, pushdownRig, raiseRig, crunchRig, facePullRig, stepRig,
        )) {
            val s = LiftSession(rig.config)
            baseline(s, rig)
            assertNull(rig.config.type.name, s.analysis.lastRep?.faults?.firstOrNull()?.id)
            assertTrue(rig.config.type.name, s.analysis.lastRep!!.calibration)
            rep(s, rig)
            rep(s, rig)
            assertEquals(rig.config.type.name, 4, s.analysis.reps)
            val last = s.analysis.lastRep!!
            assertTrue("${rig.config.type} judged", last.judged)
            assertTrue("${rig.config.type} faults ${last.faults.map { it.id }}", last.faults.isEmpty())
            val summary = s.endSet()
            assertEquals(4, summary.totalReps)
            assertEquals(2, summary.attemptedReps) // the 2 baseline reps are never judged
            assertEquals(2, summary.cleanReps)
            assertEquals(100f, summary.formScore, 0.001f)
        }
    }

    @Test
    fun liftsWithoutImplementedFaultsCountRepsButNeverJudgeOrSendFormData() {
        val hip = Rig(Lifts.deadlift, 80f, 172f, ::bridge)
        val rdl = Rig(Lifts.romanianDeadlift, 172f, 105f, ::bridge)
        val row = Rig(Lifts.bentOverRow, 170f, 80f, ::curl)
        val counting = listOf(
            Rig(Lifts.barbellSquat, 172f, 90f, ::lunge),
            Rig(Lifts.benchPress, 170f, 80f, ::curl),
            Rig(Lifts.seatedCableRow, 170f, 80f, ::curl),
            Rig(Lifts.legPress, 95f, 165f, ::lunge),
            Rig(Lifts.legCurl, 170f, 70f, ::lunge),
            Rig(Lifts.calfRaise, 100f, 140f, ::calf),
        )
        for (rig in listOf(hip, rdl, row) + counting) {
            val s = LiftSession(rig.config)
            repeat(3) { rep(s, rig) }
            assertEquals(rig.config.type.name, "Counting only: form is not judged for this lift", s.analysis.notice)
            val summary = s.endSet()
            assertEquals(rig.config.type.name, 3, summary.totalReps)
            assertEquals(0, summary.attemptedReps)
            val request = summary.toSubmitLiftSetRequest()
            assertEquals("other", request.exerciseType)
            assertEquals(3, request.repCount)
            assertNull(request.cleanCount)
            assertNull(request.attemptedCount)
            assertNull(request.formscore)
        }
    }

    // ---- (b) each fault is flagged on the right landmarks -------------------------------------------

    @Test
    fun eachFaultIsFlaggedOnItsLandmarksAndHighlighted() {
        for ((rig, c) in cases) {
            val s = LiftSession(rig.config)
            baseline(s, rig, peak = rig.peak)
            rep(s, rig)
            val clean = s.analysis.lastRep!!
            assertTrue("${c.id}: clean rep must be judged", clean.judged)
            assertTrue("${c.id}: clean rep flagged ${clean.faults.map { it.id }}", clean.faults.isEmpty())

            rep(s, rig, fault = c.id, peak = c.peak ?: rig.peak)
            val last = s.analysis.lastRep!!
            val report = last.faults.firstOrNull { it.id == c.id }
            assertNotNull("${c.id} not flagged; got ${last.faults.map { it.id }}", report)
            assertEquals(c.id, c.expected, report!!.landmarks.toSet())
            assertFalse(c.id, report.verified)
            assertEquals(FormState.RED, s.analysis.state)
            assertTrue(c.id, c.expected.all { it in s.analysis.activeLandmarks })
            val summary = s.endSet()
            assertTrue(c.id, summary.rejectedReps >= 1)
            assertTrue(c.id, report.cue in summary.rejectionReasons)
        }
    }

    // ---- (c) low visibility => "cannot see", never a guess ------------------------------------------

    @Test
    fun lowVisibilityReportsCannotSeeInsteadOfAFault() {
        for ((rig, c) in cases) {
            val s = LiftSession(rig.config)
            baseline(s, rig)
            val cue = rig.config.faults.first { it.id == c.id }.cue
            val saw = rep(s, rig, fault = c.id, peak = c.peak ?: rig.peak, hide = hideFor(rig, c))
            assertTrue("${c.id}: no 'Cannot see' notice", saw)
            val summary = s.endSet()
            assertFalse("${c.id}: reported while unseen", cue in summary.rejectionReasons)
        }
    }

    @Test
    fun cannotSeeNamesTheBodyPartAndEntersJudgedRepResult() {
        val s = LiftSession(Lifts.bicepsCurl)
        baseline(s, curlRig)
        rep(s, curlRig, fault = "torso_swing", hide = setOf(23, 24)) // the rep joint (arm) stays visible
        val last = s.analysis.lastRep!!
        assertTrue(last.cannotSee.toString(), "hips" in last.cannotSee)
        assertTrue(last.faults.none { it.id == "torso_swing" })
    }

    // ---- (d) thresholds are relative to the user's own baseline -------------------------------------

    @Test
    fun aFaultAlreadyPresentInTheBaselineIsNotFlagged() {
        for ((rig, c) in cases) {
            val s = LiftSession(rig.config)
            val peak = c.peak ?: rig.peak
            baseline(s, rig, fault = c.id, peak = peak)
            rep(s, rig, fault = c.id, peak = peak)
            rep(s, rig, fault = c.id, peak = peak)
            assertTrue(
                "${c.id} flagged although it is the user's normal: ${s.analysis.lastRep!!.faults.map { it.id }}",
                s.analysis.lastRep!!.faults.none { it.id == c.id },
            )
        }
    }

    @Test
    fun aChangeBelowTheThresholdIsNotFlagged() {
        val s = LiftSession(Lifts.lunge)
        baseline(s, lungeRig)
        rep(s, lungeRig, fault = "small_lean") // ~9.5 deg < 15 deg threshold
        assertTrue(s.analysis.lastRep!!.faults.none { it.id == "excess_trunk_lean" })
        rep(s, lungeRig, fault = "excess_trunk_lean") // ~26 deg
        assertTrue(s.analysis.lastRep!!.faults.any { it.id == "excess_trunk_lean" })
    }

    @Test
    fun firstRepsLearnTheBaselineAndAreNotJudged() {
        val s = LiftSession(Lifts.lunge)
        rep(s, lungeRig, fault = "excess_trunk_lean") // would be a fault later
        val first = s.analysis.lastRep!!
        assertTrue(first.calibration)
        assertFalse(first.judged)
        assertTrue(first.faults.isEmpty())
        val summary = s.endSet()
        assertEquals(1, summary.totalReps)
        assertEquals(0, summary.attemptedReps)
        assertNull(summary.toSubmitLiftSetRequest().formscore)
    }

    // ---- plank -----------------------------------------------------------------------------------

    private fun plank(fault: String?): Fig {
        val f = Fig()
        for (s in 0..1) {
            f.set(7 + s, 0.12f, if (fault == "head_drop") 0.58f else 0.5f)
            f.set(11 + s, 0.2f, 0.5f)
            f.set(13 + s, 0.2f + if (fault == "elbow_position") 0.08f else 0f, 0.62f)
            f.set(23 + s, 0.5f, 0.5f + when (fault) { "hip_sag" -> 0.05f; "hip_pike" -> -0.05f; else -> 0f })
            f.set(27 + s, 0.8f, 0.5f)
        }
        return f
    }

    private fun hold(s: LiftSession, frames: Int, fault: String? = null, hide: Set<Int> = emptySet()) =
        repeat(frames) { s.onFrame(frame(plank(fault), Lifts.plank, hide)) }

    private val plankCases = mapOf(
        "hip_sag" to setOf(11, 23, 27),
        "hip_pike" to setOf(11, 23, 27),
        "head_drop" to setOf(7, 11, 23),
        "elbow_position" to setOf(11, 13),
    )

    @Test
    fun plankCountsHoldSecondsNotReps() {
        val s = LiftSession(Lifts.plank)
        hold(s, 100)
        assertTrue("hold=${s.analysis.holdSeconds}", s.analysis.holdSeconds in 9..10)
        assertEquals(FormState.GREEN, s.analysis.state)
        assertNull(s.analysis.notice)
        val summary = s.endSet()
        assertEquals(0, summary.totalReps)
        assertEquals(9.9f, summary.holdSeconds!!, 0.2f)
        assertTrue(summary.rejectionReasons.isEmpty())
    }

    @Test
    fun plankFlagsEachFaultOnItsLandmarksAfterLearningTheBaseline() {
        for ((id, expected) in plankCases) {
            val s = LiftSession(Lifts.plank)
            hold(s, 40) // > 3 s baseline
            hold(s, 10, fault = id)
            assertEquals(id, FormState.RED, s.analysis.state)
            assertTrue(id, expected.all { it in s.analysis.activeLandmarks })
            val summary = s.endSet()
            val cue = Lifts.plank.faults.first { it.id == id }.cue
            assertTrue("$id: ${summary.rejectionReasons}", cue in summary.rejectionReasons)
            if (id == "hip_sag" || id == "hip_pike") {
                assertTrue("$id hold must pause while the body line is off: ${summary.holdSeconds}", summary.holdSeconds!! < 4.5f)
            }
        }
    }

    @Test
    fun plankFaultPresentInTheBaselineIsNotFlagged() {
        for (id in plankCases.keys) {
            val s = LiftSession(Lifts.plank)
            hold(s, 50, fault = id)
            assertTrue(id, s.endSet().rejectionReasons.isEmpty())
        }
    }

    @Test
    fun plankLowVisibilityReportsCannotSee() {
        for ((id, expected) in plankCases) {
            val s = LiftSession(Lifts.plank)
            hold(s, 40)
            val hide = expected.flatMap { listOf(it, if (it % 2 == 1) it + 1 else it - 1) }.toSet()
            hold(s, 10, fault = id, hide = hide)
            assertTrue(id, s.analysis.notice!!.startsWith("Cannot see"))
            val cue = Lifts.plank.faults.first { it.id == id }.cue
            assertFalse(id, cue in s.endSet().rejectionReasons)
        }
    }

    @Test
    fun aLandmarkNeedsConsecutiveGoodFramesAfterADropout() {
        val s = LiftSession(Lifts.plank)
        hold(s, 5)
        hold(s, 1, hide = (0..32).toSet())
        hold(s, 1)
        assertTrue(s.analysis.notice!!.startsWith("Cannot see"))
        hold(s, 1)
        assertTrue(s.analysis.notice!!.startsWith("Cannot see"))
        hold(s, 1) // third consecutive good frame
        assertFalse(s.analysis.notice?.startsWith("Cannot see") == true)
    }

    @Test
    fun everyLiftDeclaresItsSourceAndVerifiedFlagFromTheResearchFile() {
        val sources = Lifts.all.flatMap { it.faults }.mapNotNull { it.source }
        // lunge, goblet squat and step-up knee valgus (the only sourced fault in form_rules.json for these lifts)
        assertEquals(List(3) { "https://doi.org/10.1177/0363546504269591" }, sources)
        assertTrue(Lifts.all.flatMap { it.faults }.none { it.verified })
        assertEquals(22, Lifts.all.size)
    }
}
