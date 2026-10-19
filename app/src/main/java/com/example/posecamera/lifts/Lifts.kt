package com.example.posecamera.lifts

import com.example.posecamera.pose.ExerciseType

private const val EAR = 7
private const val SHOULDER = 11
private const val ELBOW = 13
private const val WRIST = 15
private const val HIP = 23
private const val KNEE = 25
private const val ANKLE = 27
private const val HEEL = 29
private const val FOOT = 31

private const val HEWETT_2005 = "https://doi.org/10.1177/0363546504269591"

private val kneeAngle = Measure(Kind.ANGLE, HIP, KNEE, ANKLE)
private val elbowAngle = Measure(Kind.ANGLE, SHOULDER, ELBOW, WRIST)
private val hipAngle = Measure(Kind.ANGLE, SHOULDER, HIP, KNEE)
private val trunkTilt = Measure(Kind.TILT, HIP, SHOULDER)
private val shoulderAbduction = Measure(Kind.ANGLE, HIP, SHOULDER, ELBOW)

/**
 * Batch 2 (same rule). Counting only because camera_ok_at_45=false: barbell squat, bench press, seated cable
 * row, leg press, leg curl. Counting only by choice: calf raise (landmark jitter ~ signal size).
 * Skipped faults on lifts that do have checks, with reason:
 * - goblet squat: elbow_position (not measurable).
 * - pull-up: shoulder_shrug_dead_hang (not implemented; same ear-shoulder measure as the press shrug, add if wanted);
 *   chin-vs-bar needs the nose landmark (index 0, no left/right pair), so incomplete range uses elbow angle instead.
 * - lat pulldown: shoulder_shrug (not implemented), behind_neck_pull (needs nose/ear x vs wrist; no left/right-pair fit).
 * - triceps pushdown/dip: dip is not counted (rest-at-top cycle); dip_excess_depth_shoulder (dip only).
 * - lateral raise: plane_scaption (not measurable).
 * - crunch/sit-up: neck_pulling, momentum_swing (need velocity/neck model, not in Measure); partial_range has
 *   no source here on purpose (the JSON's JOSPT 2006 link is EMG, not a kinematic criterion).
 * - face pull: no_external_rotation (not measurable).
 * - step-up: trail_leg_push_off (not measurable).
 * Sides.HIGHER (working leg on the step) is new in LiftModel/LiftSession for the step-up.
 */

/**
 * Batch 1 lifts, declared as data from data/form/form_rules.json. Rule used: a fault is implemented
 * only if the JSON marks it measurable AND its lift has camera_ok_at_45 = true.
 *
 * Skipped, with reason:
 * - lunge: none skipped.
 * - conventional deadlift (camera_ok_at_45=false): lumbar_rounding (not measurable: no spine landmarks; its
 *   Shoji 2025 source is the only verified=true fault, still unmeasurable), hips_rise_first,
 *   incomplete_lockout, knee_valgus (camera_ok_at_45=false: bar/plates and hinge occlude the body),
 *   bar_drift_from_body (not measurable: no bar landmark).
 * - Romanian deadlift (camera_ok_at_45=false): knee_bend_too_much, insufficient_hinge (camera), lumbar_rounding,
 *   bar_drift (not measurable).
 * - overhead press: none skipped, but lumbar_lean_back is weak at 45 deg (needs side view).
 * - bent-over row (camera_ok_at_45=false: near-side limb occludes far side): trunk_too_upright_swing,
 *   incomplete_pull, asymmetry (camera); lumbar_rounding (not measurable).
 * - biceps curl: none skipped.
 * - glute bridge: lumbar_hyperextension (not measurable: no pelvis/spine landmarks).
 * - plank: none skipped.
 * Lifts with zero implemented faults only count reps; their form is never judged or uploaded.
 *
 * Thresholds are CHANGES FROM THE USER'S OWN BASELINE (first [BASELINE_REPS] reps, or first
 * [PLANK_BASELINE_MILLIS] of a plank). Units: degrees, or fractions of the named [Scale].
 * Rep-detection bands are absolute defaults (the baseline needs reps first). All numbers are project defaults.
 */
object Lifts {
    val lunge = LiftConfig(
        type = ExerciseType.LUNGE,
        catalogSlug = "dumbbell-lunges",
        repJoint = kneeAngle,
        restHigh = true,
        leaveRest = 150f, peak = 115f, startReturn = 130f, backAtRest = 160f,
        startHint = "Start each rep standing tall.",
        faults = listOf(
            Fault(
                "front_knee_valgus", "Push your knee out over your toes; don't let it cave in.",
                View.FRONT, Measure(Kind.INWARD, HIP, KNEE, ANKLE, Sides.EACH, Scale.LEG),
                Stat.PER_FRAME, Dir.ABOVE, 0.08f, source = HEWETT_2005,
            ),
            Fault(
                "excess_trunk_lean", "Keep your chest up; you are leaning more than usual.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABOVE, 15f,
            ),
            Fault(
                "insufficient_depth", "Lower a bit further; your front knee bent less than usual.",
                View.DIAGONAL_45, Measure(Kind.ANGLE, HIP, KNEE, ANKLE, Sides.FRONT),
                Stat.REP_MIN, Dir.ABOVE, 15f,
            ),
            Fault(
                "heel_lift_front_foot", "Keep your front heel on the floor.",
                View.SIDE, Measure(Kind.DY, HEEL, FOOT, sides = Sides.FRONT, scale = Scale.TORSO),
                Stat.PER_FRAME, Dir.ABOVE, 0.10f,
            ),
            Fault(
                "wide_balance_sway", "You are swaying sideways; steady your hips over your feet.",
                View.FRONT, Measure(Kind.DX, ANKLE, HIP, scale = Scale.LEG),
                Stat.PER_FRAME, Dir.ABS, 0.25f,
            ),
        ),
    )

    // Rep counting only: every fault in form_rules.json is camera_ok_at_45=false or not measurable.
    val deadlift = LiftConfig(
        type = ExerciseType.DEADLIFT,
        catalogSlug = "barbell-deadlift",
        repJoint = hipAngle,
        restHigh = false,
        leaveRest = 115f, peak = 165f, startReturn = 150f, backAtRest = 105f,
        startHint = "Start each rep with the weight on the floor.",
        faults = emptyList(),
    )

    val romanianDeadlift = LiftConfig(
        type = ExerciseType.ROMANIAN_DEADLIFT,
        catalogSlug = "romanian-deadlift",
        repJoint = hipAngle,
        restHigh = true,
        leaveRest = 150f, peak = 120f, startReturn = 135f, backAtRest = 162f,
        startHint = "Start each rep standing tall.",
        faults = emptyList(),
    )

    val overheadPress = LiftConfig(
        type = ExerciseType.OVERHEAD_PRESS,
        catalogSlug = "standing-military-press",
        repJoint = elbowAngle,
        restHigh = false,
        leaveRest = 105f, peak = 140f, startReturn = 130f, backAtRest = 95f,
        startHint = "Start each rep with the weight at shoulder height.",
        faults = listOf(
            Fault(
                "lumbar_lean_back", "Keep your ribs down and torso upright; don't lean back to finish.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABOVE, 12f,
            ),
            Fault(
                "incomplete_lockout", "Press all the way up until your elbows are straight.",
                View.ANY, elbowAngle, Stat.REP_MAX, Dir.BELOW, 12f,
            ),
            Fault(
                "asymmetry", "Press both arms up evenly.",
                View.FRONT, Measure(Kind.DY, SHOULDER, WRIST, sides = Sides.DIFF, scale = Scale.TORSO),
                Stat.PER_FRAME, Dir.ABS, 0.15f,
            ),
            Fault(
                "shoulder_shrug_elevation", "Keep your shoulders down, away from your ears.",
                View.FRONT, Measure(Kind.DY, EAR, SHOULDER, sides = Sides.EACH, scale = Scale.SHOULDER_WIDTH),
                Stat.PER_FRAME, Dir.BELOW, 0.10f,
            ),
        ),
    )

    val bentOverRow = LiftConfig(
        type = ExerciseType.BENT_OVER_ROW,
        catalogSlug = "bent-over-barbell-row",
        repJoint = elbowAngle,
        restHigh = true,
        leaveRest = 150f, peak = 100f, startReturn = 115f, backAtRest = 160f,
        startHint = "Start each rep with your arms hanging straight.",
        faults = emptyList(),
    )

    val bicepsCurl = LiftConfig(
        type = ExerciseType.BICEPS_CURL,
        catalogSlug = "dumbbell-bicep-curl",
        repJoint = elbowAngle,
        restHigh = true,
        leaveRest = 140f, peak = 70f, startReturn = 90f, backAtRest = 150f,
        startHint = "Start each rep with your arms straight down.",
        faults = listOf(
            Fault(
                "torso_swing", "Keep your body still; don't swing the weight up.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABS, 10f,
            ),
            Fault(
                "elbow_drift_forward", "Keep your elbows pinned at your sides.",
                View.SIDE, Measure(Kind.ANGLE, HIP, SHOULDER, ELBOW),
                Stat.PER_FRAME, Dir.ABOVE, 20f,
            ),
            Fault(
                "incomplete_rom", "Curl all the way up; your range is shorter than usual.",
                View.DIAGONAL_45, elbowAngle, Stat.REP_MIN, Dir.ABOVE, 15f,
            ),
            Fault(
                "asymmetry", "Your arms are curling unevenly.",
                View.FRONT, Measure(Kind.ANGLE, SHOULDER, ELBOW, WRIST, Sides.DIFF),
                Stat.PER_FRAME, Dir.ABS, 25f,
            ),
        ),
    )

    val gluteBridge = LiftConfig(
        type = ExerciseType.GLUTE_BRIDGE,
        catalogSlug = "barbell-glute-bridge",
        repJoint = hipAngle,
        restHigh = false,
        leaveRest = 135f, peak = 160f, startReturn = 150f, backAtRest = 125f,
        startHint = "Start each rep lying down with your hips on the floor.",
        faults = listOf(
            Fault(
                "incomplete_lockout", "Lift your hips higher until shoulders, hips and knees line up.",
                View.SIDE, hipAngle, Stat.REP_MAX, Dir.BELOW, 12f,
            ),
            Fault(
                "pelvic_asymmetry", "One hip is lower than the other; lift both evenly.",
                View.FRONT, Measure(Kind.Y, HIP, sides = Sides.DIFF, scale = Scale.SHOULDER_WIDTH),
                Stat.PER_FRAME, Dir.ABS, 0.08f,
            ),
            Fault(
                "knee_splay", "Keep your knees steady; don't let them fall in or out.",
                View.FRONT, Measure(Kind.INWARD, HIP, KNEE, ANKLE, Sides.EACH, Scale.LEG),
                Stat.PER_FRAME, Dir.ABS, 0.10f,
            ),
        ),
    )

    val plank = LiftConfig(
        type = ExerciseType.PLANK,
        catalogSlug = "plank",
        repJoint = null,
        restHigh = true,
        leaveRest = 0f, peak = 0f, startReturn = 0f, backAtRest = 0f,
        startHint = "Hold a plank; the timer runs while your body line stays steady.",
        faults = listOf(
            Fault(
                "hip_sag", "Lift your hips; your body line is dropping.",
                View.SIDE, Measure(Kind.LINE, SHOULDER, HIP, ANKLE),
                Stat.PER_FRAME, Dir.ABOVE, 12f, holdGate = true,
            ),
            Fault(
                "hip_pike", "Lower your hips; they are rising above your body line.",
                View.SIDE, Measure(Kind.LINE, SHOULDER, HIP, ANKLE),
                Stat.PER_FRAME, Dir.BELOW, 12f, holdGate = true,
            ),
            Fault(
                "head_drop", "Keep your neck in line with your spine.",
                View.SIDE, Measure(Kind.ANGLE, EAR, SHOULDER, HIP),
                Stat.PER_FRAME, Dir.ABS, 15f,
            ),
            Fault(
                "elbow_position", "Keep your elbows under your shoulders.",
                View.FRONT, Measure(Kind.DX, SHOULDER, ELBOW, scale = Scale.UPPER_ARM),
                Stat.PER_FRAME, Dir.ABS, 0.30f,
            ),
        ),
    )

    // ---- Batch 2 ---------------------------------------------------------------------------------

    val gobletSquat = LiftConfig(
        type = ExerciseType.GOBLET_SQUAT,
        catalogSlug = "goblet-squat",
        repJoint = kneeAngle,
        restHigh = true,
        leaveRest = 150f, peak = 115f, startReturn = 130f, backAtRest = 160f,
        startHint = "Start each rep standing tall.",
        faults = listOf(
            Fault(
                "insufficient_depth", "Squat a bit lower; your knees bent less than usual.",
                View.DIAGONAL_45, kneeAngle, Stat.REP_MIN, Dir.ABOVE, 15f,
            ),
            Fault(
                "knee_valgus", "Push your knees out over your toes; don't let them cave in.",
                View.FRONT, Measure(Kind.INWARD, HIP, KNEE, ANKLE, Sides.EACH, Scale.LEG),
                Stat.PER_FRAME, Dir.ABOVE, 0.08f, source = HEWETT_2005,
            ),
            Fault(
                "weight_drifts_forward_trunk_lean", "Keep your chest up; you are leaning forward more than usual.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABOVE, 15f,
            ),
        ),
    )

    // Counting only (camera_ok_at_45=false: bar and plates occlude shoulders/hips).
    val barbellSquat = LiftConfig(
        type = ExerciseType.BARBELL_SQUAT,
        catalogSlug = "barbell-squat",
        repJoint = kneeAngle,
        restHigh = true,
        leaveRest = 150f, peak = 115f, startReturn = 130f, backAtRest = 160f,
        startHint = "Start each rep standing tall with the bar racked on your back.",
        faults = emptyList(),
    )

    // Counting only (camera_ok_at_45=false: phone at 45 deg sees mostly the bench side).
    val benchPress = LiftConfig(
        type = ExerciseType.BENCH_PRESS,
        catalogSlug = "barbell-bench-press-medium-grip",
        repJoint = elbowAngle,
        restHigh = true,
        leaveRest = 150f, peak = 100f, startReturn = 120f, backAtRest = 160f,
        startHint = "Start each rep with the bar locked out above your chest.",
        faults = emptyList(),
    )

    // Counting only (camera_ok_at_45=false: machine, seat and cable occlude the body).
    val seatedCableRow = LiftConfig(
        type = ExerciseType.SEATED_CABLE_ROW,
        catalogSlug = "seated-cable-rows",
        repJoint = elbowAngle,
        restHigh = true,
        leaveRest = 150f, peak = 100f, startReturn = 120f, backAtRest = 160f,
        startHint = "Start each rep with your arms straight.",
        faults = emptyList(),
    )

    val pullUp = LiftConfig(
        type = ExerciseType.PULL_UP,
        catalogSlug = "pullups",
        repJoint = elbowAngle,
        restHigh = true,
        leaveRest = 150f, peak = 100f, startReturn = 115f, backAtRest = 160f,
        startHint = "Start each rep hanging with your arms straight.",
        faults = listOf(
            Fault(
                "incomplete_rom_chin_below_bar", "Pull higher; you are not getting as high as usual.",
                View.FRONT, elbowAngle, Stat.REP_MIN, Dir.ABOVE, 15f,
            ),
            Fault(
                "kipping_swing", "Keep your body still; don't swing to get up.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABOVE, 12f,
            ),
            Fault(
                "asymmetry", "One side is pulling higher than the other; pull evenly.",
                View.FRONT, Measure(Kind.ANGLE, SHOULDER, ELBOW, WRIST, Sides.DIFF),
                Stat.PER_FRAME, Dir.ABS, 25f,
            ),
        ),
    )

    val latPulldown = LiftConfig(
        type = ExerciseType.LAT_PULLDOWN,
        catalogSlug = "wide-grip-lat-pulldown",
        repJoint = elbowAngle,
        restHigh = true,
        leaveRest = 150f, peak = 105f, startReturn = 120f, backAtRest = 160f,
        startHint = "Start each rep with your arms straight overhead.",
        faults = listOf(
            Fault(
                "trunk_lean_back_momentum", "Stay upright; don't lean back to pull the bar down.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABOVE, 12f,
            ),
            Fault(
                "incomplete_rom", "Pull the bar lower; you are stopping short of your usual range.",
                View.FRONT, elbowAngle, Stat.REP_MIN, Dir.ABOVE, 15f,
            ),
        ),
    )

    // Pushdown only: a dip starts at the top, which this rest-at-bottom rep cycle does not count.
    val tricepsPushdown = LiftConfig(
        type = ExerciseType.TRICEPS_PUSHDOWN,
        catalogSlug = "triceps-pushdown",
        repJoint = elbowAngle,
        restHigh = false,
        leaveRest = 105f, peak = 150f, startReturn = 135f, backAtRest = 95f,
        startHint = "Start each rep with your elbows bent at your sides.",
        faults = listOf(
            Fault(
                "elbow_flare_drift", "Keep your elbows pinned at your sides.",
                View.FRONT, Measure(Kind.ANGLE, HIP, SHOULDER, ELBOW, Sides.EACH),
                Stat.PER_FRAME, Dir.ABOVE, 20f,
            ),
            Fault(
                "torso_lean_momentum", "Stay upright; don't lean over the stack to push.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABOVE, 12f,
            ),
        ),
    )

    val lateralRaise = LiftConfig(
        type = ExerciseType.LATERAL_RAISE,
        catalogSlug = "side-lateral-raise",
        repJoint = shoulderAbduction,
        restHigh = false,
        leaveRest = 30f, peak = 70f, startReturn = 55f, backAtRest = 20f,
        startHint = "Start each rep with your arms down at your sides.",
        faults = listOf(
            Fault(
                "raise_above_shoulder", "Stop at shoulder height; you are raising higher than usual.",
                View.FRONT, shoulderAbduction, Stat.REP_MAX, Dir.ABOVE, 20f,
            ),
            Fault(
                "torso_swing", "Keep your body still; don't swing the weights up.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABOVE, 12f,
            ),
            Fault(
                "shrug_trap_dominance", "Keep your shoulders down, away from your ears.",
                View.FRONT, Measure(Kind.DY, EAR, SHOULDER, sides = Sides.EACH, scale = Scale.SHOULDER_WIDTH),
                Stat.PER_FRAME, Dir.BELOW, 0.10f,
            ),
        ),
    )

    // Counting only (camera_ok_at_45=false: machine, sled and plates occlude the legs).
    val legPress = LiftConfig(
        type = ExerciseType.LEG_PRESS,
        catalogSlug = "leg-press",
        repJoint = kneeAngle,
        restHigh = false,
        leaveRest = 110f, peak = 150f, startReturn = 135f, backAtRest = 100f,
        startHint = "Start each rep with your knees bent.",
        faults = emptyList(),
    )

    // Counting only (camera_ok_at_45=false: machine occludes the lower legs; prone position).
    val legCurl = LiftConfig(
        type = ExerciseType.LEG_CURL,
        catalogSlug = "lying-leg-curls",
        repJoint = kneeAngle,
        restHigh = true,
        leaveRest = 150f, peak = 85f, startReturn = 105f, backAtRest = 160f,
        startHint = "Start each rep with your legs straight.",
        faults = emptyList(),
    )

    // Counting only by choice: JSON has camera_ok_at_45=true, but heel/foot landmark jitter is comparable to the
    // ankle range of motion (form_rules.json note), so neither judging nor the angle bands below are trustworthy yet.
    val calfRaise = LiftConfig(
        type = ExerciseType.CALF_RAISE,
        catalogSlug = "standing-calf-raises",
        repJoint = Measure(Kind.ANGLE, KNEE, ANKLE, FOOT),
        restHigh = false,
        leaveRest = 115f, peak = 130f, startReturn = 122f, backAtRest = 110f,
        startHint = "Start each rep with your heels on the floor.",
        faults = emptyList(),
    )

    val crunch = LiftConfig(
        type = ExerciseType.CRUNCH,
        catalogSlug = "crunches",
        repJoint = hipAngle,
        restHigh = true,
        leaveRest = 130f, peak = 115f, startReturn = 122f, backAtRest = 134f,
        startHint = "Start each rep lying down with your knees bent.",
        faults = listOf(
            Fault(
                "partial_range", "Keep each rep the same; yours is shorter or longer than usual.",
                View.SIDE, hipAngle, Stat.REP_MIN, Dir.ABS, 15f,
            ),
            Fault(
                "feet_lift", "Keep your feet flat on the floor.",
                View.SIDE, Measure(Kind.DY, HEEL, FOOT, sides = Sides.BEST, scale = Scale.TORSO),
                Stat.PER_FRAME, Dir.ABOVE, 0.10f,
            ),
        ),
    )

    val facePull = LiftConfig(
        type = ExerciseType.FACE_PULL,
        catalogSlug = "face-pull",
        repJoint = elbowAngle,
        restHigh = true,
        leaveRest = 140f, peak = 90f, startReturn = 110f, backAtRest = 155f,
        startHint = "Start each rep with your arms straight out in front.",
        faults = listOf(
            Fault(
                "elbows_below_wrists", "Keep your elbows high, at or above your hands.",
                View.FRONT, Measure(Kind.DY, ELBOW, WRIST, sides = Sides.EACH, scale = Scale.UPPER_ARM),
                Stat.PER_FRAME, Dir.BELOW, 0.10f,
            ),
            Fault(
                "trunk_lean_back", "Stay upright; don't lean back to pull.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABOVE, 12f,
            ),
        ),
    )

    val stepUp = LiftConfig(
        type = ExerciseType.STEP_UP,
        catalogSlug = "dumbbell-step-ups",
        repJoint = Measure(Kind.ANGLE, HIP, KNEE, ANKLE, Sides.HIGHER),
        restHigh = false,
        leaveRest = 130f, peak = 150f, startReturn = 140f, backAtRest = 120f,
        startHint = "Start each rep with one foot on the step and that knee bent.",
        faults = listOf(
            Fault(
                "knee_valgus", "Push your working knee out over your toes; don't let it cave in.",
                View.FRONT, Measure(Kind.INWARD, HIP, KNEE, ANKLE, Sides.HIGHER, Scale.LEG),
                Stat.PER_FRAME, Dir.ABOVE, 0.08f, source = HEWETT_2005,
            ),
            Fault(
                "excess_trunk_lean", "Keep your chest up; you are leaning more than usual.",
                View.SIDE, trunkTilt, Stat.PER_FRAME, Dir.ABOVE, 15f,
            ),
            Fault(
                "incomplete_extension", "Stand fully tall at the top of each step.",
                View.DIAGONAL_45, Measure(Kind.ANGLE, HIP, KNEE, ANKLE, Sides.HIGHER),
                Stat.REP_MAX, Dir.BELOW, 12f,
            ),
        ),
    )

    val all = listOf(
        lunge, deadlift, romanianDeadlift, overheadPress, bentOverRow, bicepsCurl, gluteBridge, plank,
        gobletSquat, barbellSquat, benchPress, seatedCableRow, pullUp, latPulldown, tricepsPushdown,
        lateralRaise, legPress, legCurl, calfRaise, crunch, facePull, stepUp,
    )
}
