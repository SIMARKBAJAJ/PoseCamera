package com.example.posecamera.demo

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/*
 * Exercise demos drawn in code: a tiny forward-kinematics stick figure.
 * Sagittal-plane convention: figure faces +x, y is up, z is the figure's left.
 * Every "bearing" is degrees clockwise from straight up (0 = up, 90 = forward, 180 = down).
 * ALL keyframe angles below are illustrative, not clinical norms (animation only).
 */

/**
 * Angles in degrees, in this order inside [a]:
 * 0 trunk lean from vertical (forward +; negative = head toward -x, used lying down),
 * 1 hip / 2 knee = interior angles (180 = straight), 3 ankle = world foot pitch (0 flat, + toes down,
 *   -135 = toes up-and-back along a 45 deg leg-press plate),
 * 4 shoulder = angle trunk->upper arm (180 = arm hangs when upright), 5 elbow interior,
 * 6 hip2 / 7 knee2 = the other leg (default same as the first), 8-9 root offset x,y.
 */
class DemoPose(
    trunk: Float, hip: Float, knee: Float, ankle: Float, shoulder: Float, elbow: Float,
    hip2: Float = hip, knee2: Float = knee, rootX: Float = 0f, rootY: Float = 0f,
) {
    val a = floatArrayOf(trunk, hip, knee, ankle, shoulder, elbow, hip2, knee2, rootX, rootY)
}

class DemoKey(val phase: String, val pose: DemoPose)

/** Allowed range per angle slot (same order as DemoPose.a); root offsets are not range-checked. */
val JOINT_RANGES: Array<ClosedFloatingPointRange<Float>> = arrayOf(
    -120f..100f, 40f..230f, 30f..180f, -135f..70f, 0f..230f, 20f..180f, 40f..230f, 30f..180f,
)

private fun k(phase: String, trunk: Float, hip: Float, knee: Float, ankle: Float, sh: Float, el: Float,
              hip2: Float = hip, knee2: Float = knee, rootX: Float = 0f, rootY: Float = 0f) =
    DemoKey(phase, DemoPose(trunk, hip, knee, ankle, sh, el, hip2, knee2, rootX, rootY))

/** [keys] loop first -> ... -> last -> first. [target] is the good-form phase shown as the ghost. */
enum class DemoLift(val label: String, val keys: List<DemoKey>, val target: Int) {
    SQUAT("Squat (bodyweight)", listOf(
        k("start", 0f, 180f, 180f, 0f, 90f, 180f),
        k("bottom", 40f, 70f, 70f, 0f, 50f, 180f)), 1),
    PUSH_UP("Push-up", listOf(
        k("top", 71.2f, 180f, 180f, 40f, 108.8f, 180f, rootX = 0.1f),
        k("bottom", 85f, 180f, 180f, 40f, 185f, 90f, rootX = 0.1f)), 1),
    LUNGE("Lunge", listOf(
        k("start", 5f, 160f, 170f, 0f, 175f, 180f, hip2 = 183f, knee2 = 175f),
        k("bottom", 5f, 95f, 90f, 0f, 175f, 180f, hip2 = 175f, knee2 = 99f)), 1),
    DEADLIFT("Deadlift (conventional)", listOf(
        k("setup", 50f, 80f, 120f, 0f, 130f, 180f),
        k("lockout", 0f, 180f, 180f, 0f, 180f, 180f)), 1),
    ROMANIAN_DEADLIFT("Romanian deadlift", listOf(
        k("top", 0f, 180f, 170f, 0f, 180f, 180f),
        k("bottom", 70f, 100f, 160f, 0f, 110f, 180f)), 1),
    OVERHEAD_PRESS("Overhead press", listOf(
        k("rack", 0f, 180f, 180f, 0f, 150f, 35f),
        k("lockout", 0f, 180f, 180f, 0f, 5f, 175f)), 1),
    BENT_OVER_ROW("Bent-over row", listOf(
        k("hang", 65f, 105f, 160f, 0f, 115f, 180f),
        k("top", 65f, 105f, 160f, 0f, 200f, 85f)), 1),
    BICEPS_CURL("Biceps curl", listOf(
        k("bottom", 0f, 180f, 180f, 0f, 180f, 170f),
        k("top", 0f, 180f, 180f, 0f, 180f, 40f)), 1),
    GLUTE_BRIDGE("Glute bridge", listOf(
        k("start", -90f, 135f, 90f, 0f, 180f, 180f),
        k("top", -105f, 170f, 97.7f, 0f, 200f, 180f)), 1),
    PLANK("Plank", listOf(
        k("setup", 85.2f, 175f, 180f, 40f, 94.8f, 90f, rootX = 0.1f),
        k("hold", 82.1f, 180f, 180f, 40f, 97.9f, 90f, rootX = 0.1f)), 1),
    // Batch 2. Pose numbers were solved against Fk so hips/hands/feet land exactly on the props below.
    GOBLET_SQUAT("Goblet squat", listOf(
        k("start", 0f, 180f, 180f, 0f, 160f, 35f),
        k("bottom", 35f, 65f, 65f, 0f, 125f, 35f)), 1),
    BARBELL_SQUAT("Barbell squat", listOf(
        k("start", 0f, 180f, 180f, 0f, 200f, 40f),
        k("bottom", 45f, 65f, 70f, 0f, 155f, 40f)), 1),
    BENCH_PRESS("Bench press", listOf(
        k("lockout", -90f, 180f, 90f, 0f, 90f, 175f),
        k("bottom", -90f, 180f, 90f, 0f, 190f, 80f)), 1),
    SEATED_ROW("Seated cable row", listOf(
        k("reach", 15f, 85f, 110f, 0f, 75f, 180f),
        k("pull", -5f, 105f, 110f, 0f, 195f, 80f)), 1),
    PULL_UP("Pull-up / chin-up", listOf( // hands pinned to the bar every frame, see DEMO_JOINT_PIN
        k("hang", 0f, 175f, 160f, 0f, 0f, 180f), // dead hang: arms straight overhead, feet 0.086 off the floor
        k("top", -10f, 175f, 160f, 0f, 175f, 40f)), 1),
    LAT_PULLDOWN("Lat pulldown", listOf(
        k("reach", -10f, 100f, 90f, 0f, 10f, 170f),
        k("pull", -10f, 100f, 90f, 0f, 175f, 70f)), 1),
    TRICEPS_PUSHDOWN("Triceps pushdown", listOf(
        k("top", 5f, 175f, 175f, 0f, 175f, 60f),
        k("bottom", 5f, 175f, 175f, 0f, 175f, 180f)), 1),
    LATERAL_RAISE("Lateral raise", listOf(
        k("down", 0f, 180f, 180f, 0f, 175f, 160f),
        k("top", 0f, 180f, 180f, 0f, 90f, 160f)), 1), // sideways raise: shoulder angle is frontal-plane, see DEMO_ABDUCT
    LEG_PRESS("Leg press", listOf(
        // back on a 45 deg pad; hip 78.5 was solved so the ankle moves exactly up the 45 deg rail (bottom -> top).
        // Foot pitch -135: toes point up the plate, plate perpendicular to the rail. Arms down the sides, hands on
        // the seat handles just under the hips (shoulder 196 / elbow 152: elbow sits between the trunk line and the pad).
        k("bottom", -45f, 40f, 95f, -135f, 196f, 152f),
        k("extended", -45f, 78.5f, 168f, -135f, 196f, 152f)), 1),
    LEG_CURL("Leg curl (seated)", listOf(
        k("extended", -10f, 100f, 175f, 0f, 100f, 120f),
        k("curled", -10f, 100f, 90f, 0f, 100f, 120f)), 1),
    CALF_RAISE("Calf raise", listOf(
        k("down", 0f, 180f, 180f, 0f, 180f, 180f),
        k("up", 0f, 180f, 180f, 55f, 180f, 180f)), 1),
    CRUNCH("Crunch", listOf(
        k("down", -90f, 150f, 120f, 0f, 150f, 50f),
        k("up", -35f, 95f, 120f, 0f, 150f, 50f)), 1),
    FACE_PULL("Face pull", listOf(
        k("reach", 0f, 180f, 180f, 0f, 90f, 175f),
        k("pull", 0f, 180f, 180f, 0f, 100f, 70f)), 1),
    // Stance foot pinned on the box every frame (DEMO_JOINT_PIN). "drive": the rear foot lifts behind (knee 90) before
    // swinging forward, so it passes over the box's front edge instead of through it (also on the way back down).
    STEP_UP("Step-up", listOf(
        k("foot on box", 10f, 105f, 155.357f, 0f, 175f, 180f, hip2 = 170f, knee2 = 180f),
        k("drive", 6f, 135f, 165f, 0f, 175f, 180f, hip2 = 160f, knee2 = 90f),
        k("top", 0f, 180f, 180f, 0f, 175f, 180f, hip2 = 110f, knee2 = 90f),
        k("step down", 6f, 135f, 165f, 0f, 175f, 180f, hip2 = 160f, knee2 = 90f)), 2),
}

/** Demo picker sections (every lift appears exactly once). */
val DEMO_GROUPS: List<Pair<String, List<DemoLift>>> by lazy { listOf(
    "Legs" to listOf(DemoLift.SQUAT, DemoLift.GOBLET_SQUAT, DemoLift.BARBELL_SQUAT, DemoLift.LUNGE,
        DemoLift.ROMANIAN_DEADLIFT, DemoLift.GLUTE_BRIDGE, DemoLift.LEG_PRESS, DemoLift.LEG_CURL,
        DemoLift.CALF_RAISE, DemoLift.STEP_UP),
    "Push" to listOf(DemoLift.PUSH_UP, DemoLift.OVERHEAD_PRESS, DemoLift.BENCH_PRESS,
        DemoLift.TRICEPS_PUSHDOWN, DemoLift.LATERAL_RAISE),
    "Pull" to listOf(DemoLift.DEADLIFT, DemoLift.BENT_OVER_ROW, DemoLift.SEATED_ROW, DemoLift.PULL_UP,
        DemoLift.LAT_PULLDOWN, DemoLift.BICEPS_CURL, DemoLift.FACE_PULL),
    "Core" to listOf(DemoLift.PLANK, DemoLift.CRUNCH),
) }

enum class DemoView(val label: String, val yawDeg: Float) {
    FRONT("Front", 90f), SIDE("Side", 0f), DIAGONAL("45 deg", 45f)
}

/** General guidance only, not sourced from any study or professional. */
const val CUES_DISCLAIMER = "General guidance, not sourced. Not medical advice."

val DEMO_CUES: Map<DemoLift, List<String>> by lazy { mapOf(
    DemoLift.SQUAT to listOf("Keep your chest up", "Knees follow the line of your toes", "Heels stay down"),
    DemoLift.PUSH_UP to listOf("Body in one straight line", "Elbows about 45 degrees from your sides", "Lower under control"),
    DemoLift.LUNGE to listOf("Stand tall, step far enough", "Front knee over the middle of your foot", "Push through the front heel"),
    DemoLift.DEADLIFT to listOf("Bar stays close to your legs", "Flat back, chest up", "Stand tall at the top, do not lean back"),
    DemoLift.ROMANIAN_DEADLIFT to listOf("Push your hips back", "Soft knees, back stays flat", "Weight slides down your thighs"),
    DemoLift.OVERHEAD_PRESS to listOf("Squeeze your glutes, ribs down", "Press straight up, head moves through", "Do not arch your lower back"),
    DemoLift.BENT_OVER_ROW to listOf("Hinge forward, keep your back flat", "Pull elbows toward your hips", "No swinging"),
    DemoLift.BICEPS_CURL to listOf("Elbows stay by your sides", "No swinging your body", "Lower slowly"),
    DemoLift.GLUTE_BRIDGE to listOf("Push through your heels", "Squeeze at the top", "Ribs down, do not over-arch"),
    DemoLift.PLANK to listOf("Body in one straight line", "Do not let your hips sag or pike", "Keep breathing"),
    DemoLift.GOBLET_SQUAT to listOf("Hold the weight at your chest", "Elbows inside your knees at the bottom", "Chest up, heels down"),
    DemoLift.BARBELL_SQUAT to listOf("Bar rests on your upper back, not your neck", "Brace, then sit between your hips", "Knees follow your toes"),
    DemoLift.BENCH_PRESS to listOf("Feet planted, shoulder blades back", "Lower to your mid chest", "Press up, keep your wrists stacked"),
    DemoLift.SEATED_ROW to listOf("Sit tall, do not rock", "Pull elbows back along your sides", "Squeeze your shoulder blades, return slowly"),
    DemoLift.PULL_UP to listOf("Start from a full hang", "Pull your chest toward the bar", "Lower under control, no kicking"),
    DemoLift.LAT_PULLDOWN to listOf("Lean back slightly, chest up", "Pull the bar to your upper chest", "Do not yank with your body"),
    DemoLift.TRICEPS_PUSHDOWN to listOf("Elbows pinned to your sides", "Straighten fully at the bottom", "Let your hands rise slowly"),
    DemoLift.LATERAL_RAISE to listOf("Slight bend in your elbows", "Raise to about shoulder height", "Lower slowly, no swinging"),
    DemoLift.LEG_PRESS to listOf("Back and hips stay on the pad", "Knees follow your toes", "Do not lock your knees hard at the top"),
    DemoLift.LEG_CURL to listOf("Hips stay on the seat", "Curl smoothly, no jerking", "Return slowly"),
    DemoLift.CALF_RAISE to listOf("Rise as high as you can", "Pause at the top", "Lower slowly through the full range"),
    DemoLift.CRUNCH to listOf("Lift your shoulders, not your neck", "Lower back stays on the floor", "Exhale as you curl up"),
    DemoLift.FACE_PULL to listOf("Pull toward your face, elbows high", "Squeeze your shoulder blades", "Keep your ribs down"),
    DemoLift.STEP_UP to listOf("Whole foot on the box", "Drive through the front heel", "Step down under control"),
) }

/**
 * Prop line segment in world coords (z = depth). If [follow] >= 0 the segment is offset by that figure joint's
 * x/y each frame (bars held in the hands, a leg-press sled under the feet).
 */
class DemoProp(
    private val x0: Float, private val y0: Float, private val z0: Float,
    private val x1: Float, private val y1: Float, private val z1: Float, private val follow: Int = -1,
    /** Also drawn on the ghost figure (default: only for followers, so a held bar stays in the ghost hands). */
    val inGhost: Boolean = follow >= 0,
    /** End 1 stays at its world point while end 0 follows (a cable from the hands up to a fixed pulley). */
    private val anchored1: Boolean = false,
    /** Views the line is drawn in (hidden where it would only duplicate another line or lie on the body line). */
    private val views: Set<DemoView> = ALL_VIEWS,
    /** Views where the line is drawn as a thin dim outline so the body reads in front of it. */
    private val faint: Set<DemoView> = emptySet(),
) {
    val follows get() = follow >= 0
    fun isFaint(view: DemoView) = view in faint
    fun shownIn(view: DemoView) = view in views
    /** Ghost copies only in Side: in Front / 45 deg they nest around the figure's own props and add clutter. */
    fun ghostIn(view: DemoView) = inGhost && view == DemoView.SIDE && shownIn(view)
    private fun moves(e: Int) = follow >= 0 && !(e == 1 && anchored1)
    fun wx(e: Int, x: FloatArray) = (if (e == 0) x0 else x1) + (if (moves(e)) x[follow] else 0f)
    fun wy(e: Int, y: FloatArray) = (if (e == 0) y0 else y1) + (if (moves(e)) y[follow] else 0f)
    fun wz(e: Int) = if (e == 0) z0 else z1
}

// Surface heights shared by the props and the contact points (so figure and prop cannot drift apart).
private const val HEAD_R = Fk.L_HEAD * 0.7f
private const val BENCH_Y = Fk.L_SHANK; private const val ROW_SEAT_Y = 0.285f; private const val SEAT_Y = 0.246f
private const val PULL_BAR_X = 0.05f; private const val PULL_BAR_Y = 1.2f; private const val PULL_RACK_X = 0.3f
private const val LAT_COL_X = 0.45f; private const val LAT_TOP_Y = 1.0f
/** Bench pad top this far under the body line: as close as the lowest elbow (0.032 under the torso line) allows. */
private const val BENCH_GAP = 0.045f
private const val LP_Y = 0.12f; private const val LP_NECK_Y = LP_Y + 0.212f
private const val BOX_X = 0.3815f; private const val BOX_H = 0.2f
/** Props are drawn this far under the body line so a seated figure does not hide its own support. */
private const val GAP = 0.03f
private val ALL_VIEWS = DemoView.entries.toSet()
/** Lines that in Front only repeat another line (x collapses) or sit on the trunk line (z = 0 machine parts). */
private val NO_FRONT = setOf(DemoView.SIDE, DemoView.DIAGONAL)

/**
 * Box-shaped support with a flat top at [y] - [gap] and a leg at each corner (bench, seat, step box). The top
 * is [w] wide each side of the body so it reads as a 3D box in the 45 deg / front views (a line in side view).
 */
private fun stand(x0: Float, x1: Float, y: Float, gap: Float = GAP, w: Float = 0.1f): List<DemoProp> {
    val t = y - gap
    // Each line drawn once per view. Front: the x1 top edge and legs repeat the x0 ones. 45 deg (no camera tilt): all
    // four top edges fall on one horizontal line, covered once by the x0 edge + the +w edge; the (x1, -w) leg lands
    // on the (x0, +w) one when the box is exactly 2w long.
    val side = setOf(DemoView.SIDE); val no45 = setOf(DemoView.SIDE, DemoView.FRONT)
    return listOf(
        DemoProp(x0, t, -w, x1, t, -w, views = no45), DemoProp(x0, t, w, x1, t, w),
        DemoProp(x0, t, -w, x0, t, w), DemoProp(x1, t, -w, x1, t, w, views = side),
        DemoProp(x0, 0f, -w, x0, t, -w), DemoProp(x0, 0f, w, x0, t, w),
        DemoProp(x1, 0f, -w, x1, t, -w, views = if (kotlin.math.abs(x1 - x0 - 2 * w) < 1e-4f) side else NO_FRONT),
        DemoProp(x1, 0f, w, x1, t, w, views = NO_FRONT))
}

/** Pad line behind a trunk leaning [t] degrees (pelvis at (0, [py])): [off] behind the trunk line, from [s0] to [s1] along it. */
private fun backPad(py: Float, t: Float, off: Float, s0: Float, s1: Float, views: Set<DemoView>): DemoProp {
    val r = t * (Math.PI / 180.0).toFloat(); val dx = sin(r); val dy = cos(r)
    return DemoProp(s0 * dx - off * dy, py + s0 * dy + off * dx, 0f, s1 * dx - off * dy, py + s1 * dy + off * dx, 0f, views = views)
}

private fun handBar() = DemoProp(0f, 0f, -0.3f, 0f, 0f, 0.3f, follow = Fk.WRI)

/**
 * A weight plate on each end of the hand bar: octagon of radius [r] in the x-y plane, so a z-aligned bar (a dot in
 * side view) reads as a plate ring in side, an edge-on plate in front, an ellipse in 45 deg.
 */
private fun barPlates(r: Float, zEnd: Float = 0.3f) = listOf(-zEnd, zEnd).flatMap { z -> (0 until 8).map { i ->
    val a0 = i * Math.PI / 4; val a1 = (i + 1) * Math.PI / 4
    // edge-on in Front every edge lands on one vertical line: keep only the half ring (edges 6, 7, 0, 1) that covers it once
    DemoProp((r * cos(a0)).toFloat(), (r * sin(a0)).toFloat(), z, (r * cos(a1)).toFloat(), (r * sin(a1)).toFloat(), z, Fk.WRI,
        views = if (i in 2..5) NO_FRONT else ALL_VIEWS)
} }

/**
 * Pull-up rack: two uprights in front of the figure plus the hand bar, joined by arms (Side: gallows shape; 45 deg: one
 * arm). Uprights cannot stand under the bar ends: DemoFigureTest.barLiftsSideViewClean rejects any prop (all views)
 * that crosses a bone in Side, and a vertical line at the bar x runs through the hanging figure.
 */
private fun pullUpRack(): List<DemoProp> = listOf(DemoProp(PULL_BAR_X, PULL_BAR_Y, -0.25f, PULL_BAR_X, PULL_BAR_Y, 0.25f)) +
    listOf(-0.25f, 0.25f).flatMap { z -> listOf(
        DemoProp(PULL_RACK_X, 0f, z, PULL_RACK_X, PULL_BAR_Y, z),
        // Only the +z arm in 45 deg (the -z arm would lie on the bar line) and none in Front (it is a point there)
        DemoProp(PULL_RACK_X, PULL_BAR_Y, z, PULL_BAR_X, PULL_BAR_Y, z,
            views = if (z < 0f) setOf(DemoView.SIDE) else setOf(DemoView.SIDE, DemoView.DIAGONAL))) }

/**
 * Lat-pulldown machine: column in front of the feet, top beam back to a pulley straight above the reach-key hands,
 * cable from the bar (hands) to the pulley, thigh pad out to the column. Pulley x is solved from the key.
 */
private fun latMachine(): List<DemoProp> {
    val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
    val lift = DemoLift.LAT_PULLDOWN
    demoSolve(lift, lift.keys[0].pose.a, x, y, z)
    val px = x[Fk.WRI]; val padY = SEAT_Y + 0.05f
    // Front: column / beam / pad (z = 0) would lie on the trunk line and the cable; only the cable is drawn there
    return listOf(
        // 45 deg: pad, beam and cable are hidden (they cross the arms); column stays, the hand bar stays on the hands
        DemoProp(0.12f, padY, 0f, LAT_COL_X, padY, 0f, views = setOf(DemoView.SIDE)), // thigh pad, fixed to the column
        DemoProp(LAT_COL_X, 0f, 0f, LAT_COL_X, LAT_TOP_Y, 0f, views = NO_FRONT), // column
        DemoProp(LAT_COL_X, LAT_TOP_Y, 0f, px, LAT_TOP_Y, 0f, views = setOf(DemoView.SIDE)), // top beam, pulley at its end
        DemoProp(0f, 0f, 0f, px, LAT_TOP_Y, 0f, Fk.WRI, anchored1 = true, views = setOf(DemoView.SIDE, DemoView.FRONT)), // cable: hands -> pulley
    )
}

/** Leg-press plate offset this far up the rail from the sole, so the foot reads as resting on it. */
const val LP_FOOT_GAP = 0.015f
/** Leg-press plate half-width (z): both feet (z +-0.06) on one plate, edges clear of the arms (z +-0.1) in front view. */
const val LP_PLATE_W = 0.12f
/** Seat-base height (pad and seat top sit a head radius under the body line, see backPad). */
private const val LP_SEAT_Y = LP_Y - HEAD_R * 0.70710677f

/**
 * 45 deg leg-press sled: ONE plate (a rectangle spanning both feet in z) riding on the left ankle, perpendicular to
 * the rail, its lower edge on the rail; in Side it is also drawn on the ghost (at the extended position). ONE rail at
 * z = 0 (hidden in Front, where it would lie on the trunk line), from the seat's front corner up past the
 * plate's highest position. The plate's lower edge is far enough below the heel ([LO]) that the rail line runs under
 * the whole figure and meets the seat top just in front of the hips; rail ends are solved from the keys so they
 * cannot drift from the poses. Returns the seat base + front post too (their front edge is the rail start).
 */
private fun legPressSled(): List<DemoProp> {
    val q = 0.70710677f
    fun px(along: Float, up: Float) = (up - along) * q // along = toward the toes on the plate, up = up the rail
    fun py(along: Float, up: Float) = (up + along) * q
    val lo = -0.14f; val hi = 0.14f // plate extent along itself from the heel; toe is at +L_FOOT
    val lx = px(lo, LP_FOOT_GAP); val ly = py(lo, LP_FOOT_GAP)
    val ux = px(hi, LP_FOOT_GAP); val uy = py(hi, LP_FOOT_GAP)
    val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
    val lift = DemoLift.LEG_PRESS
    demoSolve(lift, lift.keys[0].pose.a, x, y, z)
    val r0x = x[Fk.ANK] + lx - (y[Fk.ANK] + ly - LP_SEAT_Y) // rail line (45 deg) meets the seat top here
    demoSolve(lift, lift.keys[1].pose.a, x, y, z)
    // end = start + length along the exact 45 deg line (length: the plate's top position along the rail + 0.03).
    // Not the top key's own point: its ankle sits ~1e-4 off the bottom key's rail line, which would tilt the rail.
    val len = (x[Fk.ANK] + lx - r0x + y[Fk.ANK] + ly - LP_SEAT_Y) * q + 0.03f
    val r1x = r0x + len * q; val r1y = LP_SEAT_Y + len * q
    val w = LP_PLATE_W; val a = Fk.ANK
    // Front: the feet sit at head height, so the full plate (lower edge 0.14 below the heel) would box the torso and
    // the rail would lie on the trunk line. There the plate is drawn only from just under the heels (fx, fy) to its
    // upper edge: two side lines at z = +-w (on the long edges, so 3D views see nothing new) and the upper edge.
    val fx = px(-0.03f, LP_FOOT_GAP); val fy = py(-0.03f, LP_FOOT_GAP)
    val front = setOf(DemoView.FRONT)
    return listOf(
        DemoProp(lx, ly, w, ux, uy, w, a, views = NO_FRONT), DemoProp(lx, ly, -w, ux, uy, -w, a, views = NO_FRONT), // long edges
        DemoProp(lx, ly, -w, lx, ly, w, a, views = NO_FRONT), DemoProp(ux, uy, -w, ux, uy, w, a, views = NO_FRONT), // lower / upper edge
        // Front: the plate is only a thin dim outline behind the figure (sides + upper edge); no rail / pad / seat there
        DemoProp(fx, fy, w, ux, uy, w, a, views = front, faint = front), DemoProp(fx, fy, -w, ux, uy, -w, a, views = front, faint = front),
        DemoProp(ux, uy, -w, ux, uy, w, a, views = front, faint = front),
        DemoProp(r0x, LP_SEAT_Y, 0f, r1x, r1y, 0f, views = NO_FRONT), // rail
        DemoProp(-0.0495f, LP_SEAT_Y, 0f, r0x, LP_SEAT_Y, 0f, views = NO_FRONT), // seat base
        DemoProp(r0x, 0f, 0f, r0x, LP_SEAT_Y, 0f, views = NO_FRONT), // seat front post
    )
}

/** Lifts not listed have no prop. Props with a follow joint are also drawn on the ghost in Side (see [DemoProp.ghostIn]). */
val DEMO_PROPS: Map<DemoLift, List<DemoProp>> by lazy { mapOf(
    DemoLift.BARBELL_SQUAT to listOf(DemoProp(-0.02f, 0.02f, -0.25f, -0.02f, 0.02f, 0.25f, follow = Fk.SH)),
    DemoLift.DEADLIFT to listOf(handBar()), DemoLift.OVERHEAD_PRESS to listOf(handBar()),
    // pad covers head (-0.47) to past the hips (0); pad top BENCH_GAP under the torso line (head circle sinks in a little)
    DemoLift.BENCH_PRESS to stand(-0.5f, 0.12f, BENCH_Y, BENCH_GAP, 0.12f) + handBar() + barPlates(0.1f),
    DemoLift.SEATED_ROW to stand(-0.12f, 0.08f, ROW_SEAT_Y),
    DemoLift.PULL_UP to pullUpRack(),
    DemoLift.LAT_PULLDOWN to stand(-0.12f, 0.08f, SEAT_Y) + latMachine() + handBar(),
    DemoLift.LEG_PRESS to listOf(
        backPad(LP_Y, -45f, HEAD_R, 0f, 0.42f, NO_FRONT), // seat back: parallel to the trunk, head rests on it
        DemoProp(-0.0495f, 0f, 0f, -0.0495f, LP_SEAT_Y, 0f, views = NO_FRONT), // seat rear post
    ) + legPressSled(),
    DemoLift.LEG_CURL to stand(-0.1f, 0.2f, SEAT_Y, 0.05f) + backPad(SEAT_Y, -10f, 0.06f, -0.05f, 0.36f, NO_FRONT),
    DemoLift.STEP_UP to stand(BOX_X - 0.16f, BOX_X + 0.16f, BOX_H, 0f, 0.12f),
) }

/** Lifts whose pelvis is pinned to a fixed height (on a seat) instead of ground-fitted: the feet swing, the seat does not. */
val DEMO_PIN: Map<DemoLift, Float> by lazy { mapOf(DemoLift.LEG_PRESS to LP_Y, DemoLift.LEG_CURL to SEAT_Y) }

/** View picked when the lift is selected (lifts not listed keep the current view). Leg press moves in x, hidden from the front. */
val DEMO_VIEW: Map<DemoLift, DemoView> by lazy { mapOf(DemoLift.LEG_PRESS to DemoView.SIDE) }

/** Lifts whose shoulder angle (slot 4) is sideways abduction in the frontal plane (z) instead of sagittal flexion. */
val DEMO_ABDUCT: Set<DemoLift> by lazy { setOf(DemoLift.LATERAL_RAISE) }

/**
 * Lifts with one joint pinned to a fixed world point (x, y) every frame: the whole figure is shifted onto it, so
 * in-betweens cannot slide or lift it off (stance foot on the step box, hands on the pull-up bar). Root offsets ignored.
 */
val DEMO_JOINT_PIN: Map<DemoLift, Triple<Int, Float, Float>> by lazy { mapOf(
    DemoLift.STEP_UP to Triple(Fk.ANK, BOX_X, BOX_H), DemoLift.PULL_UP to Triple(Fk.WRI, PULL_BAR_X, PULL_BAR_Y)) }

/** [Fk.solve] with this lift pelvis / joint pin / abduction mode applied. Use this, not Fk.solve, for anything lift-specific. */
fun demoSolve(lift: DemoLift, a: FloatArray, x: FloatArray, y: FloatArray, z: FloatArray) {
    Fk.solve(a, x, y, z, DEMO_PIN[lift] ?: Float.NaN, lift in DEMO_ABDUCT)
    val (j, px, py) = DEMO_JOINT_PIN[lift] ?: return
    val ox = px - x[j]; val oy = py - y[j]
    for (i in 0 until Fk.N) { x[i] += ox; y[i] += oy }
}

private fun at(y: Float, vararg joints: Int) = joints.map { it to y }
private val FEET get() = at(0f, Fk.ANK, Fk.ANK + 1, Fk.TOE, Fk.TOE + 1)
private val TOES get() = at(0f, Fk.TOE, Fk.TOE + 1)
private val HANDS get() = at(0f, Fk.WRI, Fk.WRI + 1)

/**
 * Contact points that must touch their surface (joint to surface height y) in every key. Lifts not listed:
 * both feet on the ground. Checked at keyframes only (in-betweens are interpolated).
 */
val DEMO_TOUCH: Map<DemoLift, List<Pair<Int, Float>>> by lazy { mapOf(
    DemoLift.PUSH_UP to TOES + HANDS,
    DemoLift.PLANK to TOES + HANDS,
    DemoLift.GLUTE_BRIDGE to TOES + at(0f, Fk.HEAD),
    DemoLift.BENCH_PRESS to FEET + at(BENCH_Y, Fk.PELVIS, Fk.NECK, Fk.HEAD),
    DemoLift.SEATED_ROW to FEET + at(ROW_SEAT_Y, Fk.PELVIS),
    DemoLift.PULL_UP to at(PULL_BAR_Y, Fk.WRI, Fk.WRI + 1),
    DemoLift.LAT_PULLDOWN to FEET + at(SEAT_Y, Fk.PELVIS),
    DemoLift.LEG_PRESS to at(LP_Y, Fk.PELVIS) + at(LP_NECK_Y, Fk.NECK), // feet: on the sled plate, see DemoFigureTest
    DemoLift.LEG_CURL to at(SEAT_Y, Fk.PELVIS),
    DemoLift.CALF_RAISE to TOES,
    DemoLift.CRUNCH to FEET + at(0f, Fk.PELVIS),
    DemoLift.STEP_UP to at(BOX_H, Fk.ANK, Fk.TOE),
) }
fun demoTouch(lift: DemoLift): List<Pair<Int, Float>> = DEMO_TOUCH[lift] ?: FEET

/** Skeleton: 17 joints, 16 bones. */
object Fk {
    const val N = 17
    const val HEAD = 0; const val NECK = 1; const val PELVIS = 2
    // L joint = base index, R = base + 1
    const val SH = 3; const val HIP = 5; const val ELB = 7; const val WRI = 9
    const val KNEE = 11; const val ANK = 13; const val TOE = 15

    const val L_TRUNK = 0.30f; const val L_HEAD = 0.10f; const val L_UARM = 0.186f; const val L_FARM = 0.146f
    const val L_THIGH = 0.245f; const val L_SHANK = 0.246f; const val L_FOOT = 0.12f
    const val HALF_SH = 0.10f; const val HALF_HIP = 0.06f

    val BONE_A = intArrayOf(0, 1, 1, 1, 2, 2, 3, 7, 4, 8, 5, 11, 13, 6, 12, 14)
    val BONE_B = intArrayOf(1, 2, 3, 4, 5, 6, 7, 9, 8, 10, 11, 13, 15, 12, 14, 16)
    val BONE_LEN = floatArrayOf(
        L_HEAD, L_TRUNK, HALF_SH, HALF_SH, HALF_HIP, HALF_HIP, L_UARM, L_FARM, L_UARM, L_FARM,
        L_THIGH, L_SHANK, L_FOOT, L_THIGH, L_SHANK, L_FOOT,
    )
    /** True for right-side joints (drawn dimmer so both sides stay readable in side view). */
    val IS_RIGHT = BooleanArray(N) { it >= 3 && it % 2 == 0 }

    private const val D2R = (Math.PI / 180.0).toFloat()
    private fun dx(b: Float) = sin(b * D2R)
    private fun dy(b: Float) = cos(b * D2R)

    /**
     * Writes world joint positions for angles [a] (no allocation). Feet/hands rest on y = 0, then root offset.
     * [pinY] (not NaN) puts the pelvis at exactly that height instead (seated lifts; root y is then ignored).
     * [abduct] makes shoulder/elbow move in the frontal plane (arms out to the sides, z) instead of sagittal.
     */
    fun solve(a: FloatArray, x: FloatArray, y: FloatArray, z: FloatArray, pinY: Float = Float.NaN, abduct: Boolean = false) {
        val t = a[0]
        x[PELVIS] = 0f; y[PELVIS] = 0f; z[PELVIS] = 0f
        x[NECK] = L_TRUNK * dx(t); y[NECK] = L_TRUNK * dy(t); z[NECK] = 0f
        x[HEAD] = x[NECK] + L_HEAD * dx(t); y[HEAD] = y[NECK] + L_HEAD * dy(t); z[HEAD] = 0f
        for (s in 0..1) {
            val zs = if (s == 0) 1f else -1f
            val sh = SH + s; val hp = HIP + s; val el = ELB + s; val wr = WRI + s
            val kn = KNEE + s; val an = ANK + s; val to = TOE + s
            x[sh] = x[NECK]; y[sh] = y[NECK]; z[sh] = zs * HALF_SH
            x[hp] = 0f; y[hp] = 0f; z[hp] = zs * HALF_HIP
            val ub = t + a[4]
            if (abduct) { // hand ends a little lower than the elbow, like pouring a jug
                val fb = ub + (180f - a[5])
                x[el] = x[sh]; y[el] = y[sh] + L_UARM * dy(ub); z[el] = z[sh] + zs * L_UARM * dx(ub)
                x[wr] = x[el]; y[wr] = y[el] + L_FARM * dy(fb); z[wr] = z[el] + zs * L_FARM * dx(fb)
            } else {
                x[el] = x[sh] + L_UARM * dx(ub); y[el] = y[sh] + L_UARM * dy(ub); z[el] = z[sh]
                val fb = ub - (180f - a[5])
                x[wr] = x[el] + L_FARM * dx(fb); y[wr] = y[el] + L_FARM * dy(fb); z[wr] = z[sh]
            }
            val th = t + (if (s == 0) a[1] else a[6])
            x[kn] = x[hp] + L_THIGH * dx(th); y[kn] = y[hp] + L_THIGH * dy(th); z[kn] = z[hp]
            val sb = th + 180f - (if (s == 0) a[2] else a[7])
            x[an] = x[kn] + L_SHANK * dx(sb); y[an] = y[kn] + L_SHANK * dy(sb); z[an] = z[hp]
            val fo = 90f + a[3]
            x[to] = x[an] + L_FOOT * dx(fo); y[to] = y[an] + L_FOOT * dy(fo); z[to] = z[hp]
        }
        var floor = min(min(y[ANK], y[ANK + 1]), min(y[TOE], y[TOE + 1]))
        floor = min(min(floor, y[HEAD]), min(y[WRI], y[WRI + 1]))
        val lift = if (!pinY.isNaN()) pinY else a[9] - floor
        for (i in 0 until N) { x[i] += a[8]; y[i] += lift }
    }

    /** Orthographic rotation about the vertical axis: screen-x of a world point. 0 = side, 90 = front. */
    fun projectX(x: Float, z: Float, cosYaw: Float, sinYaw: Float) = x * cosYaw + z * sinYaw
    fun cosYaw(view: DemoView) = cos(view.yawDeg * D2R)
    fun sinYaw(view: DemoView) = sin(view.yawDeg * D2R)
}

/** One-cycle playback: keys loop first->...->last->first; phase in [0,1). Smoothstep between keys. */
fun poseAt(lift: DemoLift, phase: Float, out: FloatArray) {
    val keys = lift.keys
    val p = (phase - floor(phase)) * keys.size
    val i = min(p.toInt(), keys.size - 1)
    val f = p - i
    val s = f * f * (3f - 2f * f)
    val from = keys[i].pose.a; val to = keys[(i + 1) % keys.size].pose.a
    for (j in out.indices) out[j] = from[j] + (to[j] - from[j]) * s
}

/**
 * One shared ground per lift/view: bounding box of every frame (all keyframes + in-betweens, which includes the
 * ghost target) so the figure and its ghost are fitted by the same scale/ground and nothing clips.
 * World y = 0 is the ground; head circles pad all sides by [PAD].
 */
class DemoFit(val minX: Float, val maxX: Float, val maxY: Float) {
    val height get() = maxY + 2 * PAD
    fun scale(w: Float, h: Float, margin: Float) = min((w - 2 * margin) / (maxX - minX), (h - 2 * margin) / height)
    /** Screen x for projected world x. */
    fun sx(px: Float, w: Float, scale: Float) = w / 2f + (px - (minX + maxX) / 2f) * scale
    /** Screen y for world y, ground line at the bottom margin. */
    fun sy(y: Float, h: Float, margin: Float, scale: Float) = h - margin - (PAD + y) * scale
    companion object { const val PAD = Fk.L_HEAD * 0.7f }
}

fun demoFit(lift: DemoLift, view: DemoView): DemoFit {
    val a = FloatArray(10)
    val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
    val c = Fk.cosYaw(view); val s = Fk.sinYaw(view)
    var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var top = 0f
    for (i in 0 until 48) {
        poseAt(lift, i / 48f, a); demoSolve(lift, a, x, y, z)
        for (j in 0 until Fk.N) {
            val p = Fk.projectX(x[j], z[j], c, s)
            lo = min(lo, p); hi = kotlin.math.max(hi, p); top = kotlin.math.max(top, y[j])
        }
        for (pr in DEMO_PROPS[lift].orEmpty()) for (e in 0..1) {
            val p = Fk.projectX(pr.wx(e, x), pr.wz(e), c, s)
            lo = min(lo, p); hi = kotlin.math.max(hi, p); top = kotlin.math.max(top, pr.wy(e, y))
        }
    }
    return DemoFit(lo - DemoFit.PAD, hi + DemoFit.PAD, top)
}

/** Scale-0 mode: exact start (second=false) or good-form target (second=true) frame, nothing between. */
fun staticPose(lift: DemoLift, second: Boolean, out: FloatArray) {
    val a = lift.keys[if (second) lift.target else 0].pose.a
    for (j in out.indices) out[j] = a[j]
}

private const val BASE_CYCLE_SECONDS = 2.4f

/** Cycle length in nanos; slower when animationScale < 1. Only meaningful for scale > 0. */
fun cycleNanos(animationScale: Float): Long = (BASE_CYCLE_SECONDS * 1e9f / animationScale).toLong()

/** Minimum nanos between redraws: every frame at scale >= 1, ~15 fps at 0.5. */
fun redrawGapNanos(animationScale: Float): Long =
    if (animationScale >= 1f) 0L else (33_000_000f / animationScale).toLong()
