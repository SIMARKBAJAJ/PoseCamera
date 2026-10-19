package com.example.posecamera.demo

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BOX_TOP = 0.2f // = BOX_H in DemoFigure.kt (private there)

class DemoFigureTest {
    private fun solve(lift: DemoLift, a: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        demoSolve(lift, a, x, y, z)
        return Triple(x, y, z)
    }

    @Test fun segmentLengthsPreservedForAllKeysAndMidpoints() {
        val a = FloatArray(10)
        for (lift in DemoLift.entries) for (ph in listOf(0f, 0.13f, 0.25f, 0.5f, 0.77f)) {
            poseAt(lift, ph, a)
            val (x, y, z) = solve(lift, a)
            for (b in Fk.BONE_A.indices) {
                val i = Fk.BONE_A[b]; val j = Fk.BONE_B[b]
                val d = sqrt((x[i] - x[j]).let { it * it } + (y[i] - y[j]).let { it * it } + (z[i] - z[j]).let { it * it })
                assertEquals("$lift bone $b", Fk.BONE_LEN[b], d, 1e-4f)
            }
        }
    }

    /** Nothing penetrates the ground, at the keys and at 100 in-between frames. */
    @Test fun nothingBelowGround() {
        val a = FloatArray(10)
        for (lift in DemoLift.entries) for (i in 0 until 100) {
            poseAt(lift, i / 100f, a)
            assertTrue("$lift frame $i", solve(lift, a).second.min() >= -1e-4f)
        }
    }

    /** Every per-lift contact point (feet on ground, back on bench, hips on seat, hands on bar...) of every key
     *  (figure and ghost share these keys) must sit exactly on its surface height. */
    @Test fun allContactPointsRestOnTheirSurface() {
        for (lift in DemoLift.entries) for (key in lift.keys) {
            val (_, y, _) = solve(lift, key.pose.a.copyOf())
            for ((joint, surfaceY) in demoTouch(lift))
                assertEquals("$lift ${key.phase} joint $joint", surfaceY, y[joint], 0.01f)
        }
    }

    /** Pelvis on seat / floor and back + head on bench or pad must hold at every in-between frame too (no mid-cycle dip). */
    @Test fun bodySupportsHoldMidCycle() {
        val a = FloatArray(10)
        val body = setOf(Fk.PELVIS, Fk.NECK, Fk.HEAD)
        for (lift in listOf(DemoLift.BENCH_PRESS, DemoLift.SEATED_ROW, DemoLift.LAT_PULLDOWN, DemoLift.LEG_PRESS,
            DemoLift.LEG_CURL, DemoLift.CRUNCH)) for (i in 0 until 100) {
            poseAt(lift, i / 100f, a)
            val (_, y, _) = solve(lift, a)
            for ((joint, surfaceY) in demoTouch(lift)) if (joint in body)
                assertEquals("$lift frame $i joint $joint", surfaceY, y[joint], 0.01f)
        }
    }

    // ---- Leg press, checked on the screen's own path: poseAt -> demoSolve -> projectX, props via wx/wy/wz ----
    private val lp = DemoLift.LEG_PRESS
    private val lpProps get() = DEMO_PROPS.getValue(lp)
    /** The single foot plate: its 4 edges (the only props that follow the figure). */
    private val lpPlate get() = lpProps.filter { it.follows }
    /** Fixed machine lines: pad, seat, posts, rail. */
    private val lpFrame get() = lpProps.filter { !it.follows }
    /** The rail: the only fixed line rising toward +x. */
    private val lpRails get() = FloatArray(Fk.N).let { o -> lpFrame.filter { it.wx(1, o) > it.wx(0, o) && it.wy(1, o) > it.wy(0, o) } }
    private val lpBones = Fk.BONE_A.indices.map { Fk.BONE_A[it] to Fk.BONE_B[it] }

    /** 2D segments as the screen draws them for one figure: bones and every prop (props follow this figure's joints). */
    private fun lpSegs(x: FloatArray, y: FloatArray, z: FloatArray, view: DemoView, props: List<DemoProp>): List<Pair<String, FloatArray>> {
        val c = Fk.cosYaw(view); val sn = Fk.sinYaw(view)
        return lpBones.map { (i, j) -> "bone $i-$j" to floatArrayOf(Fk.projectX(x[i], z[i], c, sn), y[i], Fk.projectX(x[j], z[j], c, sn), y[j]) } +
            props.mapIndexed { k, p -> "prop $k" to floatArrayOf(Fk.projectX(p.wx(0, x), p.wz(0), c, sn), p.wy(0, y),
                Fk.projectX(p.wx(1, x), p.wz(1), c, sn), p.wy(1, y)) }
    }
    /** Strict crossing: interior of both segments (touching at joints / end points does not count). */
    private fun crosses(a: FloatArray, b: FloatArray): Boolean {
        val t = cross(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3]) ?: return false
        val u = cross(b[0], b[1], b[2], b[3], a[0], a[1], a[2], a[3]) ?: return false
        return t in 1e-3f..(1f - 1e-3f) && u in 1e-3f..(1f - 1e-3f)
    }

    private fun segDist(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0; val l2 = dx * dx + dy * dy
        val u = if (l2 > 0f) (((px - x0) * dx + (py - y0) * dy) / l2).coerceIn(0f, 1f) else 0f
        val ex = x0 + u * dx - px; val ey = y0 + u * dy - py
        return sqrt(ex * ex + ey * ey)
    }

    /** Proper 2D segment intersection: parameter along the first segment, or null. */
    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float? {
        val rx = bx - ax; val ry = by - ay; val sx = dx - cx; val sy = dy - cy
        val den = rx * sy - ry * sx
        if (abs(den) < 1e-9f) return null
        val t = ((cx - ax) * sy - (cy - ay) * sx) / den; val u = ((cx - ax) * ry - (cy - ay) * rx) / den
        return if (t in 0f..1f && u in 0f..1f) t else null
    }

    /** Both feet (ankle + toe) on the one plate in every view at 100 frames; flat on it (sole parallel) in side view. */
    @Test fun legPressFeetOnPlateInEveryViewAsDrawn() {
        val a = FloatArray(10)
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        for (view in DemoView.entries) {
            val c = Fk.cosYaw(view); val sn = Fk.sinYaw(view)
            for (i in 0 until 100) {
                poseAt(lp, i / 100f, a); demoSolve(lp, a, x, y, z)
                for (s in 0..1) {
                    // plate quad from its two edges along the plate (same z at both ends)
                    val (e1, e2) = lpPlate.filter { it.shownIn(view) && it.wz(0) == it.wz(1) }
                    val qx = floatArrayOf(Fk.projectX(e1.wx(0, x), e1.wz(0), c, sn), Fk.projectX(e1.wx(1, x), e1.wz(1), c, sn),
                        Fk.projectX(e2.wx(1, x), e2.wz(1), c, sn), Fk.projectX(e2.wx(0, x), e2.wz(0), c, sn))
                    val qy = floatArrayOf(e1.wy(0, y), e1.wy(1, y), e2.wy(1, y), e2.wy(0, y))
                    for (j in listOf(Fk.ANK + s, Fk.TOE + s)) {
                        val px = Fk.projectX(x[j], z[j], c, sn); val py = y[j]
                        val sides = (0..3).map { k -> val n = (k + 1) % 4
                            (qx[n] - qx[k]) * (py - qy[k]) - (qy[n] - qy[k]) * (px - qx[k]) }.filter { abs(it) > 1e-9f }
                        val inside = sides.isNotEmpty() && (sides.all { it > 0f } || sides.all { it < 0f })
                        val d = if (inside) 0f else (0..3).minOf { k -> val n = (k + 1) % 4
                            segDist(px, py, qx[k], qy[k], qx[n], qy[n]) }
                        if (view == DemoView.SIDE) assertEquals("side frame $i joint $j plate gap", LP_FOOT_GAP, d, 0.002f)
                        else assertTrue("$view frame $i joint $j is $d off its plate", d <= LP_FOOT_GAP + 0.003f)
                    }
                }
            }
        }
    }

    /** Bottom: knees 90-100 deg; top: extended but not locked (165-170); never past either during the cycle. */
    @Test fun legPressKneeAngles() {
        fun knee(a: FloatArray, s: Int): Float {
            val (x, y, z) = solve(lp, a)
            val c = Fk.cosYaw(DemoView.SIDE); val sn = Fk.sinYaw(DemoView.SIDE)
            fun p(j: Int) = Fk.projectX(x[j], z[j], c, sn) to y[j]
            val (hx, hy) = p(Fk.HIP + s); val (kx, ky) = p(Fk.KNEE + s); val (ax, ay) = p(Fk.ANK + s)
            val v1x = hx - kx; val v1y = hy - ky; val v2x = ax - kx; val v2y = ay - ky
            return Math.toDegrees(kotlin.math.acos(((v1x * v2x + v1y * v2y) /
                (sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y))).toDouble())).toFloat()
        }
        val a = FloatArray(10)
        for (s in 0..1) {
            assertTrue("bottom knee", knee(lp.keys[0].pose.a.copyOf(), s) in 90f..100f)
            assertTrue("top knee", knee(lp.keys[1].pose.a.copyOf(), s) in 165f..170f)
            for (i in 0 until 100) { poseAt(lp, i / 100f, a); assertTrue("frame $i knee", knee(a, s) in 90f..170f) }
        }
    }

    /** The sled slides on its rail: plate lower end stays on the rail line within the rail's span, and travels. */
    @Test fun legPressPlateRidesTheRail() {
        val zero = FloatArray(Fk.N)
        assertEquals("exactly one rail", 1, lpRails.size)
        val rail = lpRails.single()
        val rx = rail.wx(0, zero); val ry = rail.wy(0, zero)
        val dx = rail.wx(1, zero) - rx; val dy = rail.wy(1, zero) - ry; val len2 = dx * dx + dy * dy
        assertEquals("rail is a 45 deg incline", dx, dy, 1e-4f)
        val plate = lpPlate.first { it.wz(0) == it.wz(1) }
        val a = FloatArray(10)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0 until 100) {
            poseAt(lp, i / 100f, a)
            val (x, y, _) = solve(lp, a)
            val px = plate.wx(0, x) - rx; val py = plate.wy(0, y) - ry
            assertEquals("frame $i plate off rail", 0f, (px * dy - py * dx) / sqrt(len2), 0.006f)
            val u = (px * dx + py * dy) / len2
            assertTrue("frame $i plate past rail end ($u)", u in -0.01f..1f)
            lo = minOf(lo, u); hi = maxOf(hi, u)
        }
        assertTrue("sled barely moves", (hi - lo) * sqrt(len2) > 0.1f)
    }

    /**
     * Side view as drawn, 100 frames: no two lines cross anywhere (every bone vs every other bone and every prop,
     * plate + rail + pad + seat); the ghost (extended key) with its own plate is clean the same way.
     * Not checked: ghost legs vs the moving figure's plate (the ghost foot sits further up the same rail, so its shin
     * passes where the live plate is whenever the live figure is lower; that is the overlay, not a drawing error).
     */
    @Test fun legPressSideViewHasNoCrossingLines() {
        val a = FloatArray(10)
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        fun check(label: String) {
            val segs = lpSegs(x, y, z, DemoView.SIDE, lpProps.filter { it.shownIn(DemoView.SIDE) })
            for (m in segs.indices) for (n in m + 1 until segs.size)
                assertTrue("$label: ${segs[m].first} crosses ${segs[n].first}", !crosses(segs[m].second, segs[n].second))
        }
        for (i in 0 until 100) { poseAt(lp, i / 100f, a); demoSolve(lp, a, x, y, z); check("frame $i") }
        staticPose(lp, true, a); demoSolve(lp, a, x, y, z); check("ghost")
    }

    /**
     * Arms hang down the sides to handles under the hips: in every view and frame neither arm segment crosses its
     * own side's thigh or shin; in side view no arm segment crosses any leg or prop line (covered above too); hands
     * end beside the hips (between trunk line and pad, above the seat). The 45 deg view does show the near (left)
     * arm passing in front of the far (right) thigh: true depth overlap of two limbs 0.16 apart in z, unavoidable
     * for any hand position below the shoulders since the left shoulder projects left of the right thigh there.
     */
    @Test fun legPressArmsBesideHipsDoNotCrossTheLegs() {
        val a = FloatArray(10)
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        for (view in DemoView.entries) for (i in 0 until 100) {
            poseAt(lp, i / 100f, a); demoSolve(lp, a, x, y, z)
            val segs = lpSegs(x, y, z, view, emptyList()).toMap()
            for (s in 0..1) for ((p, d) in listOf(Fk.SH to Fk.ELB, Fk.ELB to Fk.WRI)) for ((l0, l1) in listOf(Fk.HIP to Fk.KNEE, Fk.KNEE to Fk.ANK)) {
                val arm = segs.getValue("bone ${p + s}-${d + s}"); val leg = segs.getValue("bone ${l0 + s}-${l1 + s}")
                assertTrue("$view frame $i arm $s ${p + s}-${d + s} crosses leg ${l0 + s}-${l1 + s}", !crosses(arm, leg))
            }
            for (s in 0..1) {
                val hx = x[Fk.WRI + s] - x[Fk.PELVIS]; val hy = y[Fk.WRI + s] - y[Fk.PELVIS]
                assertTrue("hand $s beside the hip ($hx, $hy)", abs(hx) < 0.05f && abs(hy) < 0.05f)
                val behind = -(hx + hy) * 0.70710677f // distance behind the 45 deg trunk line, toward the pad
                assertTrue("hand $s between trunk and pad ($behind)", behind in 0f..Fk.L_HEAD * 0.7f)
                assertTrue("hand $s above the seat", y[Fk.WRI + s] > y[Fk.PELVIS] - Fk.L_HEAD * 0.7f * 0.70710677f)
            }
        }
    }

    /** Legs never cross the fixed machine (rail, pad, seat) in side or 45 deg view (front collapses x onto them). */
    @Test fun legPressLegsDoNotCrossTheFrame() {
        val a = FloatArray(10)
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        val legs = lpBones.filter { (i, _) -> i in Fk.HIP..Fk.HIP + 1 || i in Fk.KNEE..Fk.ANK + 1 }.map { (i, j) -> "bone $i-$j" }.toSet()
        for (view in listOf(DemoView.SIDE, DemoView.DIAGONAL)) for (i in 0 until 100) {
            poseAt(lp, i / 100f, a); demoSolve(lp, a, x, y, z)
            val segs = lpSegs(x, y, z, view, lpFrame.filter { it.shownIn(view) })
            for ((ln, l) in segs.filter { it.first in legs }) for ((pn, p) in segs.filter { it.first.startsWith("prop") })
                assertTrue("$view frame $i $ln crosses frame $pn", !crosses(l, p))
        }
    }

    /** One plate (4 edges, spanning both feet in z) and it is drawn on the ghost too, at the extended position. */
    @Test fun legPressGhostHasItsOwnPlate() {
        assertEquals("side: one plate, 4 edges", 4, lpPlate.count { it.shownIn(DemoView.SIDE) })
        assertEquals("front: plate drawn from the heels up, 3 edges", 3, lpPlate.count { it.shownIn(DemoView.FRONT) })
        assertTrue(lpPlate.filter { it.shownIn(DemoView.SIDE) }.all { it.ghostIn(DemoView.SIDE) })
        assertTrue(lpPlate.all { minOf(it.wz(0), it.wz(1)) < -Fk.HALF_HIP && maxOf(it.wz(0), it.wz(1)) > Fk.HALF_HIP || it.wz(0) == it.wz(1) })
        val (x0, y0, _) = solve(lp, lp.keys[0].pose.a.copyOf())
        val (xg, yg, _) = solve(lp, lp.keys[lp.target].pose.a.copyOf())
        val e = lpPlate.first()
        assertTrue("ghost plate is further up the rail", e.wx(0, xg) > e.wx(0, x0) + 0.05f && e.wy(0, yg) > e.wy(0, y0) + 0.05f)
    }

    /**
     * Front / 45 deg as drawn, every lift, 20 frames: no two visible prop lines lie on top of each other (collinear and
     * overlapping by more than 1e-3), and ghost copies of props are drawn in Side only.
     */
    @Test fun noPropLineDrawnTwiceInFrontOr45() {
        val a = FloatArray(10)
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        fun overlap(s: FloatArray, t: FloatArray): Float {
            val l1 = sqrt((s[2] - s[0]) * (s[2] - s[0]) + (s[3] - s[1]) * (s[3] - s[1]))
            val l2 = sqrt((t[2] - t[0]) * (t[2] - t[0]) + (t[3] - t[1]) * (t[3] - t[1]))
            if (l1 < 1e-3f || l2 < 1e-3f) return 0f
            val ux = (s[2] - s[0]) / l1; val uy = (s[3] - s[1]) / l1
            if (abs(ux * (t[3] - t[1]) - uy * (t[2] - t[0])) / l2 > 1e-3f) return 0f // not parallel
            if (abs(ux * (t[1] - s[1]) - uy * (t[0] - s[0])) > 1e-3f) return 0f // parallel, apart
            val t0 = (t[0] - s[0]) * ux + (t[1] - s[1]) * uy; val t1 = (t[2] - s[0]) * ux + (t[3] - s[1]) * uy
            return minOf(l1, maxOf(t0, t1)) - maxOf(0f, minOf(t0, t1))
        }
        for (lift in DemoLift.entries) for (view in listOf(DemoView.FRONT, DemoView.DIAGONAL)) {
            val props = DEMO_PROPS[lift].orEmpty()
            assertTrue("$lift $view ghost props", props.none { it.ghostIn(view) })
            for (i in 0 until 100 step 5) {
                poseAt(lift, i / 100f, a); demoSolve(lift, a, x, y, z)
                val segs = lpSegs(x, y, z, view, props.filter { it.shownIn(view) }).filter { it.first.startsWith("prop") }
                for (m in segs.indices) for (n in m + 1 until segs.size)
                    assertTrue("$lift $view frame $i: ${segs[m].first} drawn over ${segs[n].first}", overlap(segs[m].second, segs[n].second) <= 1e-3f)
            }
        }
    }

    /** Leg press Front: the plate (and every other visible prop) stays outside the head circle and off the trunk line. */
    @Test fun legPressFrontPropsClearHeadAndTrunk() {
        val a = FloatArray(10)
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        for (i in 0 until 100) {
            poseAt(lp, i / 100f, a); demoSolve(lp, a, x, y, z)
            val segs = lpSegs(x, y, z, DemoView.FRONT, lpProps.filter { it.shownIn(DemoView.FRONT) })
            val head = Fk.projectX(x[Fk.HEAD], z[Fk.HEAD], 0f, 1f) to y[Fk.HEAD]
            val body = segs.filter { it.first == "bone ${Fk.HEAD}-${Fk.NECK}" || it.first == "bone ${Fk.NECK}-${Fk.PELVIS}" }
            for ((pn, p) in segs.filter { it.first.startsWith("prop") }) {
                assertTrue("frame $i $pn inside the head circle", segDist(head.first, head.second, p[0], p[1], p[2], p[3]) >= Fk.L_HEAD * 0.7f)
                for ((bn, b) in body) assertTrue("frame $i $pn crosses $bn", !crosses(p, b))
            }
        }
    }

    @Test fun legPressOpensInSideView() = assertEquals(DemoView.SIDE, DEMO_VIEW[DemoLift.LEG_PRESS])

    /** Step-up as drawn: stance (front) foot stays on the box top at every frame; rear foot starts on the ground and never sinks. */
    @Test fun stepUpFrontFootOnBoxTopRearFootOnGroundAtStart() {
        val lift = DemoLift.STEP_UP
        val a = FloatArray(10)
        val feet = listOf(Fk.ANK, Fk.TOE)
        for (i in 0 until 100) {
            poseAt(lift, i / 100f, a)
            val (_, y, _) = solve(lift, a)
            for (j in feet) {
                assertEquals("frame $i front joint $j", BOX_TOP, y[j], 0.005f)
                assertTrue("frame $i rear joint ${j + 1} below ground", y[j + 1] >= -1e-4f)
            }
        }
        for (key in lift.keys.indices) { // keys are ghost frames too
            val (_, y, _) = solve(lift, lift.keys[key].pose.a.copyOf())
            for (j in feet) assertEquals("key $key front joint $j", BOX_TOP, y[j], 0.005f)
        }
        val (_, y0, _) = solve(lift, lift.keys[0].pose.a.copyOf())
        for (j in feet) assertEquals("start rear joint ${j + 1}", 0f, y0[j + 1], 0.005f)
    }

    /**
     * Step-up as drawn (side): the stance foot does not slide on the box (x fixed, every frame), and the rear shin/foot
     * never pass through the box (sampled along each segment, strictly inside the box outline = through it).
     */
    @Test fun stepUpStanceFootStillAndRearLegClearsTheBox() {
        val lift = DemoLift.STEP_UP
        val zero = FloatArray(Fk.N)
        val box = DEMO_PROPS.getValue(lift)
        val x0 = box.minOf { minOf(it.wx(0, zero), it.wx(1, zero)) }; val x1 = box.maxOf { maxOf(it.wx(0, zero), it.wx(1, zero)) }
        val a = FloatArray(10)
        val ankX = solve(lift, lift.keys[0].pose.a.copyOf()).first[Fk.ANK]
        for (i in 0 until 200) {
            poseAt(lift, i / 200f, a)
            val (x, y, _) = solve(lift, a)
            assertEquals("frame $i stance foot slides", ankX, x[Fk.ANK], 1e-4f)
            for ((j0, j1) in listOf(Fk.KNEE + 1 to Fk.ANK + 1, Fk.ANK + 1 to Fk.TOE + 1)) for (k in 0..20) {
                val px = x[j0] + (x[j1] - x[j0]) * k / 20f; val py = y[j0] + (y[j1] - y[j0]) * k / 20f
                assertTrue("frame $i rear bone $j0-$j1 inside the box ($px, $py)", !(px > x0 + 1e-3f && px < x1 - 1e-3f && py < BOX_TOP - 1e-3f))
            }
        }
    }

    /** Pull-up: both hands stay exactly on the bar (x and y) at every frame, not only at the keys. */
    @Test fun pullUpHandsStayOnTheBarEveryFrame() {
        val lift = DemoLift.PULL_UP
        val zero = FloatArray(Fk.N)
        val bar = DEMO_PROPS.getValue(lift).single { it.wz(0) != it.wz(1) } // the bar runs along z; rack lines do not
        val a = FloatArray(10)
        for (i in 0 until 100) {
            poseAt(lift, i / 100f, a)
            val (x, y, _) = solve(lift, a)
            for (w in listOf(Fk.WRI, Fk.WRI + 1)) {
                assertEquals("frame $i hand $w x", bar.wx(0, zero), x[w], 1e-4f)
                assertEquals("frame $i hand $w y", bar.wy(0, zero), y[w], 1e-4f)
            }
        }
    }

    /** Pull-up: hang = arms fully straight overhead with the feet off the floor; top = chin (head bottom) over the bar. */
    @Test fun pullUpDeadHangAndChinOverBar() {
        val lift = DemoLift.PULL_UP
        val (x0, y0, _) = solve(lift, lift.keys[0].pose.a.copyOf())
        val reach = sqrt((x0[Fk.WRI] - x0[Fk.SH]).let { it * it } + (y0[Fk.WRI] - y0[Fk.SH]).let { it * it })
        assertEquals("hang arm straight", Fk.L_UARM + Fk.L_FARM, reach, 1e-4f)
        assertEquals("hang arm vertical", x0[Fk.SH], x0[Fk.WRI], 1e-4f)
        assertTrue("hang feet off the floor", minOf(y0[Fk.ANK], y0[Fk.TOE]) > 0.05f)
        val (_, y1, _) = solve(lift, lift.keys[1].pose.a.copyOf())
        assertTrue("top chin over bar", y1[Fk.HEAD] - Fk.L_HEAD * 0.7f > y1[Fk.WRI])
    }

    private val barLifts = listOf(DemoLift.PULL_UP, DemoLift.BENCH_PRESS, DemoLift.LAT_PULLDOWN)

    /**
     * The bar reads in side view (a z-aligned bar alone projects to a dot): at every frame and in the ghost, some prop
     * line at least 0.05 long, as drawn in Side, passes within 0.1 of the hands (rack arm, plate ring, cable).
     */
    @Test fun barVisibleInSideView() {
        val a = FloatArray(10)
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        for (lift in barLifts) for (i in 0..100) {
            if (i == 100) staticPose(lift, true, a) else poseAt(lift, i / 100f, a)
            demoSolve(lift, a, x, y, z)
            val segs = lpSegs(x, y, z, DemoView.SIDE, DEMO_PROPS.getValue(lift).filter { it.shownIn(DemoView.SIDE) })
                .filter { it.first.startsWith("prop") }.map { it.second }
            assertTrue("$lift frame $i: no visible bar line in side", segs.any { s ->
                sqrt((s[2] - s[0]) * (s[2] - s[0]) + (s[3] - s[1]) * (s[3] - s[1])) > 0.05f &&
                    segDist(x[Fk.WRI], y[Fk.WRI], s[0], s[1], s[2], s[3]) <= 0.1f + 1e-3f })
        }
    }

    /** No floating machine lines: every fixed prop has an end on the ground or on another prop line (3D). */
    @Test fun machinePropsAreAttached() {
        val zero = FloatArray(Fk.N)
        fun near(p: FloatArray, q: DemoProp): Boolean { // point p on segment q, 3D
            val ax = q.wx(0, zero); val ay = q.wy(0, zero); val az = q.wz(0)
            val dx = q.wx(1, zero) - ax; val dy = q.wy(1, zero) - ay; val dz = q.wz(1) - az
            val l2 = dx * dx + dy * dy + dz * dz
            val u = if (l2 > 0f) (((p[0] - ax) * dx + (p[1] - ay) * dy + (p[2] - az) * dz) / l2).coerceIn(0f, 1f) else 0f
            val ex = ax + u * dx - p[0]; val ey = ay + u * dy - p[1]; val ez = az + u * dz - p[2]
            return sqrt(ex * ex + ey * ey + ez * ez) < 1e-3f
        }
        for (lift in barLifts + DemoLift.LEG_PRESS) {
            val props = DEMO_PROPS.getValue(lift)
            for (p in props.filter { !it.follows }) {
                val ends = (0..1).map { e -> floatArrayOf(p.wx(e, zero), p.wy(e, zero), p.wz(e)) }
                assertTrue("$lift prop floats", ends.any { e -> abs(e[1]) < 1e-4f || props.any { it !== p && !it.follows && near(e, it) } })
            }
        }
    }

    /**
     * Side view as drawn, 100 frames + ghost: pull-up and lat pulldown have no bone crossing any prop line. Bench press:
     * no bone crosses the bench, and the bench covers head to hips with its top at most 0.05 under the body line.
     * (Bench forearms do cross the plate rings: the near plate is in front of the hand, as in a photo.)
     */
    @Test fun barLiftsSideViewClean() {
        val a = FloatArray(10)
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        val zero = FloatArray(Fk.N)
        for (lift in barLifts) {
            val props = DEMO_PROPS.getValue(lift).let { ps -> if (lift == DemoLift.BENCH_PRESS) ps.filter { !it.follows } else ps }
            for (i in 0..100) {
                if (i == 100) staticPose(lift, true, a) else poseAt(lift, i / 100f, a)
                demoSolve(lift, a, x, y, z)
                val segs = lpSegs(x, y, z, DemoView.SIDE, props)
                for ((bn, b) in segs.filter { it.first.startsWith("bone") }) for ((pn, p) in segs.filter { it.first.startsWith("prop") })
                    assertTrue("$lift frame $i $bn crosses $pn", !crosses(b, p))
            }
        }
        val bench = DEMO_PROPS.getValue(DemoLift.BENCH_PRESS).filter { !it.follows }
        val top = bench.maxOf { maxOf(it.wy(0, zero), it.wy(1, zero)) }
        val x0 = bench.minOf { minOf(it.wx(0, zero), it.wx(1, zero)) }; val x1 = bench.maxOf { maxOf(it.wx(0, zero), it.wx(1, zero)) }
        for (key in DemoLift.BENCH_PRESS.keys) {
            val (bx, by, _) = solve(DemoLift.BENCH_PRESS, key.pose.a.copyOf())
            for (j in listOf(Fk.HEAD, Fk.NECK, Fk.PELVIS)) {
                assertTrue("${key.phase} joint $j on the bench (x ${bx[j]})", bx[j] in x0..x1)
                assertTrue("${key.phase} joint $j within 0.05 of the pad", by[j] - top in 0f..0.05f)
            }
        }
    }

    /** Every hand-held bar (props following a wrist) passes through both wrists as drawn, every frame, every lift. */
    @Test fun heldBarsStayInTheHands() {
        val a = FloatArray(10)
        val zero = FloatArray(Fk.N)
        // hand bars: followers with no x/y offset at either end (a squat bar sits behind the neck, a sled plate ahead
        // of the feet, bar plates ring the hands, a cable's far end stays on its pulley)
        for (lift in DemoLift.entries) for (bar in DEMO_PROPS[lift].orEmpty().filter {
            it.follows && it.wx(0, zero) == 0f && it.wy(0, zero) == 0f && it.wx(1, zero) == 0f && it.wy(1, zero) == 0f }) {
            val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
            for (i in 0 until 100) {
                poseAt(lift, i / 100f, a); demoSolve(lift, a, x, y, z)
                for (w in listOf(Fk.WRI, Fk.WRI + 1)) for (e in 0..1) {
                    assertEquals("$lift frame $i bar end $e x", x[w], bar.wx(e, x), 1e-4f)
                    assertEquals("$lift frame $i bar end $e y", y[w], bar.wy(e, y), 1e-4f)
                }
                assertTrue("$lift bar spans both hands", minOf(bar.wz(0), bar.wz(1)) < z[Fk.WRI + 1] && maxOf(bar.wz(0), bar.wz(1)) > z[Fk.WRI])
            }
        }
        assertTrue(DEMO_PROPS.getValue(DemoLift.DEADLIFT).single().follows)
        assertTrue(DEMO_PROPS.getValue(DemoLift.OVERHEAD_PRESS).single().follows)
    }

    /** Lateral raise abducts in the frontal plane: hands travel out in z (shown in Front view), not forward in x. */
    @Test fun lateralRaiseMovesSidewaysNotForward() {
        val lift = DemoLift.LATERAL_RAISE
        val (x0, _, z0) = solve(lift, lift.keys[0].pose.a.copyOf())
        val (x1, _, z1) = solve(lift, lift.keys[1].pose.a.copyOf())
        assertTrue(abs(z1[Fk.WRI]) > abs(z0[Fk.WRI]) + 0.25f)
        assertEquals(x0[Fk.SH], x0[Fk.WRI], 1e-4f)
        assertEquals(x1[Fk.SH], x1[Fk.WRI], 1e-4f)
    }

    @Test fun pickerGroupsCoverEveryLiftExactlyOnce() {
        assertEquals(DemoLift.entries.toSet(), DEMO_GROUPS.flatMap { it.second }.toSet())
        assertEquals(DemoLift.entries.size, DEMO_GROUPS.sumOf { it.second.size })
    }

    @Test fun fitNeverClipsForAllLiftsAndViewsAtCanvasAspect() {
        val a = FloatArray(10)
        val x = FloatArray(Fk.N); val y = FloatArray(Fk.N); val z = FloatArray(Fk.N)
        val margin = 12f
        for (lift in DemoLift.entries) for (view in DemoView.entries) for ((w, h) in listOf(1000f to 750f, 360f to 280f)) {
            val fit = demoFit(lift, view)
            val sc = fit.scale(w, h, margin)
            val c = Fk.cosYaw(view); val s = Fk.sinYaw(view)
            for (i in 0 until 200) {
                poseAt(lift, i / 200f, a); demoSolve(lift, a, x, y, z)
                for (j in 0 until Fk.N) {
                    val sx = fit.sx(Fk.projectX(x[j], z[j], c, s), w, sc); val sy = fit.sy(y[j], h, margin, sc)
                    val ok = sx >= margin - 1f && sx <= w - margin + 1f && sy >= margin - 1f && sy <= h - margin + 1f
                    assertTrue("$lift $view frame $i joint $j ($sx,$sy)", ok)
                }
                for (pr in DEMO_PROPS[lift].orEmpty()) for (e in 0..1) {
                    val sx = fit.sx(Fk.projectX(pr.wx(e, x), pr.wz(e), c, s), w, sc); val sy = fit.sy(pr.wy(e, y), h, margin, sc)
                    assertTrue("$lift $view frame $i prop end $e ($sx,$sy)",
                        sx >= margin - 1f && sx <= w - margin + 1f && sy >= margin - 1f && sy <= h - margin + 1f)
                }
            }
            assertEquals("$lift $view ground", h - margin - DemoFit.PAD * sc, fit.sy(0f, h, margin, sc), 1e-3f)
        }
    }

    @Test fun projectionViews() {
        // side: depth axis z vanishes; front: forward axis x vanishes; 45: both at cos45
        val x = 1f; val z = 1f
        fun p(v: DemoView) = Fk.projectX(x, z, Fk.cosYaw(v), Fk.sinYaw(v))
        assertEquals(1f, Fk.projectX(1f, 5f, Fk.cosYaw(DemoView.SIDE), Fk.sinYaw(DemoView.SIDE)), 1e-5f)
        assertEquals(5f, Fk.projectX(1f, 5f, Fk.cosYaw(DemoView.FRONT), Fk.sinYaw(DemoView.FRONT)), 1e-5f)
        assertEquals(1.41421356f, p(DemoView.DIAGONAL), 1e-5f)
    }

    @Test fun frontViewShowsShoulderWidthSideViewDoesNot() {
        val a = DemoLift.SQUAT.keys[0].pose.a.copyOf()
        val (x, _, z) = solve(DemoLift.SQUAT, a)
        fun width(v: DemoView) = abs(Fk.projectX(x[Fk.SH], z[Fk.SH], Fk.cosYaw(v), Fk.sinYaw(v)) -
            Fk.projectX(x[Fk.SH + 1], z[Fk.SH + 1], Fk.cosYaw(v), Fk.sinYaw(v)))
        assertEquals(0f, width(DemoView.SIDE), 1e-5f)
        assertEquals(2 * Fk.HALF_SH, width(DemoView.FRONT), 1e-5f)
    }

    @Test fun interpolationEndpoints() {
        val a = FloatArray(10)
        for (lift in DemoLift.entries) {
            poseAt(lift, 0f, a)
            assertArrayEq(lift.keys[0].pose.a, a)
            poseAt(lift, 1f / lift.keys.size, a)
            assertArrayEq(lift.keys[1 % lift.keys.size].pose.a, a)
            poseAt(lift, 0.999999f, a) // wraps back to first key
            assertArrayEq(lift.keys[0].pose.a, a, 1e-2f)
        }
    }

    @Test fun scaleZeroIsStaticTwoFrame() {
        assertEquals(0f, redrawGapNanos(1f).toFloat(), 0f)
        assertTrue(redrawGapNanos(0.5f) > 0)
        assertTrue(cycleNanos(0.5f) == 2 * cycleNanos(1f))
        val a = FloatArray(10)
        for (lift in DemoLift.entries) {
            staticPose(lift, false, a); assertArrayEq(lift.keys[0].pose.a, a)
            staticPose(lift, true, a); assertArrayEq(lift.keys[lift.target].pose.a, a)
        }
    }

    @Test fun everyLiftHasDistinctKeysWithinRangesAndCues() {
        assertEquals(24, DemoLift.entries.size)
        for (lift in DemoLift.entries) {
            assertTrue("$lift keys", lift.keys.size >= 2)
            assertNotEquals("$lift identical keys", lift.keys[0].pose.a.toList(), lift.keys[lift.target].pose.a.toList())
            assertTrue(lift.target in lift.keys.indices)
            assertTrue("$lift cues", DEMO_CUES[lift].orEmpty().isNotEmpty())
            for (key in lift.keys) JOINT_RANGES.forEachIndexed { i, r ->
                assertTrue("$lift ${key.phase} slot $i=${key.pose.a[i]}", key.pose.a[i] in r)
            }
        }
    }

    private fun assertArrayEq(e: FloatArray, a: FloatArray, tol: Float = 1e-4f) {
        for (i in e.indices) assertEquals(e[i], a[i], tol)
    }
}
