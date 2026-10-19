package com.example.posecamera.skills

import com.example.posecamera.lifts.Lifts

/*
 * EVERY number in this file (targets, 3 sets, 2 sessions, RPE 8, plateau 4 sessions, 21-day staleness, RPE 9 deload)
 * is an UNVERIFIED PROJECT DEFAULT: no cited source, not coach-reviewed. Not medical advice.
 * Design: docs/SKILL_TREE_DESIGN.md. Pure Kotlin, no Android.
 */
enum class Family(val label: String) { PUSH("Push"), LEGS("Legs"), PULL("Pull"), CORE("Core") }

enum class Metric { REPS, SECONDS }

class Step(
    val id: String,
    val name: String,
    val family: Family,
    val inCatalog: Boolean,
    val requires: List<String>,
    val metric: Metric,
    val target: Int,
    val sets: Int = 3,
    val sessions: Int = 2,
    val maxRpe: Double = 8.0,
    val perSide: Boolean = false,
) {
    /** Isometric step: stored nowhere in Phase 1 (no duration column), so it cannot be logged yet. */
    val hold: Boolean get() = metric == Metric.SECONDS
}

/** Camera rows store exerciseSlug = null and exerciseType squat/push-up; mirrors backend POSE_SLUG. */
val CAMERA_SLUG: Map<String, String> = mapOf("squat" to "bodyweight-squat", "push-up" to "pushups")

private fun rep(id: String, name: String, f: Family, cat: Boolean, req: List<String>, target: Int, perSide: Boolean = false) =
    Step(id, name, f, cat, req, Metric.REPS, target, perSide = perSide)

private fun hold(id: String, name: String, f: Family, cat: Boolean, req: List<String>, target: Int) =
    Step(id, name, f, cat, req, Metric.SECONDS, target)

/** Topological order: every `requires` entry appears earlier. */
val SKILL_STEPS: List<Step> = listOf(
    rep("incline-push-up", "Incline push-up", Family.PUSH, true, emptyList(), 15),
    rep("pushups", "Push-up", Family.PUSH, true, listOf("incline-push-up"), 12),
    rep("decline-push-up", "Decline push-up", Family.PUSH, true, listOf("pushups"), 10),
    rep("push-ups-close-triceps-position", "Close push-up", Family.PUSH, true, listOf("pushups"), 10),
    rep("archer-push-up", "Archer push-up", Family.PUSH, false, listOf("decline-push-up", "push-ups-close-triceps-position"), 6, true),
    rep("single-arm-push-up", "One-arm push-up", Family.PUSH, true, listOf("archer-push-up"), 3, true),
    rep("bench-dips", "Bench dip", Family.PUSH, true, emptyList(), 12),
    rep("parallel-bar-dip", "Parallel-bar dip", Family.PUSH, true, listOf("bench-dips", "pushups"), 8),
    rep("ring-dips", "Ring dip", Family.PUSH, true, listOf("parallel-bar-dip"), 6),
    hold("plank", "Plank", Family.CORE, true, emptyList(), 45),
    hold("planche-lean", "Planche lean", Family.PUSH, false, listOf("pushups", "plank"), 15),
    rep("pseudo-planche-push-up", "Pseudo-planche push-up", Family.PUSH, false, listOf("planche-lean", "decline-push-up"), 8),
    rep("bodyweight-squat", "Bodyweight squat", Family.LEGS, true, emptyList(), 20),
    rep("split-squats", "Split squat", Family.LEGS, true, listOf("bodyweight-squat"), 10, true),
    rep("bulgarian-split-squat", "Bulgarian split squat", Family.LEGS, false, listOf("split-squats"), 10, true),
    rep("single-leg-high-box-squat", "Single-leg box squat", Family.LEGS, true, listOf("split-squats"), 8, true),
    rep("assisted-pistol-squat", "Assisted pistol squat", Family.LEGS, false, listOf("single-leg-high-box-squat"), 6, true),
    rep("pistol-squat", "Pistol squat", Family.LEGS, false, listOf("assisted-pistol-squat", "bulgarian-split-squat"), 3, true),
    rep("shrimp-squat", "Shrimp squat", Family.LEGS, false, listOf("bulgarian-split-squat"), 3, true),
    hold("dead-hang", "Dead hang", Family.PULL, false, emptyList(), 30),
    rep("inverted-row", "Inverted row", Family.PULL, true, emptyList(), 10),
    rep("scapular-pull-up", "Scapular pull-up", Family.PULL, true, listOf("dead-hang"), 10),
    rep("negative-pull-up", "Negative pull-up", Family.PULL, false, listOf("scapular-pull-up", "inverted-row"), 5),
    rep("band-assisted-pull-up", "Band-assisted pull-up", Family.PULL, true, listOf("negative-pull-up"), 8),
    rep("chin-up", "Chin-up", Family.PULL, true, listOf("band-assisted-pull-up"), 5),
    rep("pullups", "Pull-up", Family.PULL, true, listOf("chin-up"), 8),
    hold("hollow-body-hold", "Hollow body hold", Family.CORE, false, listOf("plank"), 30),
    rep("flat-bench-lying-leg-raise", "Lying leg raise", Family.CORE, true, listOf("plank"), 12),
    rep("knee-hip-raise-on-parallel-bars", "Parallel-bar knee raise", Family.CORE, true, listOf("flat-bench-lying-leg-raise"), 12),
    rep("hanging-leg-raise", "Hanging leg raise", Family.CORE, true, listOf("knee-hip-raise-on-parallel-bars", "dead-hang"), 8),
    hold("tuck-l-sit", "Tuck L-sit", Family.CORE, false, listOf("hollow-body-hold", "knee-hip-raise-on-parallel-bars", "bench-dips"), 10),
    hold("l-sit", "L-sit", Family.CORE, false, listOf("tuck-l-sit"), 10),
)

val SKILL_BY_ID: Map<String, Step> = SKILL_STEPS.associateBy { it.id }

/** Slugs to hand to UserDao.skillSets. */
val SKILL_SLUGS: List<String> = SKILL_STEPS.map { it.id }

/** Steps the camera can count: derived from the app's lift registry plus the squat/push-up counter, never hand-set. */
val POSE_SLUGS: Set<String> = Lifts.all.map { it.catalogSlug }.toSet() + CAMERA_SLUG.values

val Step.poseTrackable: Boolean get() = id in POSE_SLUGS

/** Phase 1: only rep steps that exist in the backend catalog can be logged (they sync as exercise_type "other"). */
fun canLogPhase1(s: Step): Boolean = s.inCatalog && !s.hold
