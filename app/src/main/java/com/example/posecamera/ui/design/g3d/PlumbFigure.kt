package com.example.posecamera.ui.design.g3d

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.example.posecamera.demo.DEMO_PROPS
import com.example.posecamera.demo.DemoFit
import com.example.posecamera.demo.DemoLift
import com.example.posecamera.demo.DemoView
import com.example.posecamera.demo.Fk
import com.example.posecamera.demo.demoFit
import com.example.posecamera.demo.demoSolve
import com.example.posecamera.demo.poseAt
import com.example.posecamera.demo.staticPose
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.SahiColors
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Viewer camera limits and default pitch (positive pitch = camera above the figure). */
val ORBIT_MIN_PITCH = Math.toRadians(-5.0).toFloat()
val ORBIT_MAX_PITCH = Math.toRadians(40.0).toFloat()
val ORBIT_DEFAULT_PITCH = Math.toRadians(12.0).toFloat()

private const val DIST = 3f
private const val GRID_N = 9 // lines each way
private const val N_BONES = 16
private val BODY_H = Fk.L_TRUNK + Fk.L_HEAD + Fk.L_THIGH + Fk.L_SHANK
private const val COINCIDE_FRAC = 0.02f // of figure height

// Body capsule radii (world units) per bone, proximal (A) -> distal (B); 0 = no fill. Bone order = Fk.BONE_A/B.
private val FILL_R0 = floatArrayOf(0f, 0.085f, 0.035f, 0.035f, 0.04f, 0.04f, 0.04f, 0.03f, 0.04f, 0.03f, 0.06f, 0.045f, 0.025f, 0.06f, 0.045f, 0.025f)
private val FILL_R1 = floatArrayOf(0f, 0.07f, 0.03f, 0.03f, 0.04f, 0.04f, 0.03f, 0.02f, 0.03f, 0.02f, 0.045f, 0.03f, 0.02f, 0.045f, 0.03f, 0.02f)
private val OCT_C = FloatArray(8) { cos(it * Math.PI / 4).toFloat() }
private val OCT_S = FloatArray(8) { sin(it * Math.PI / 4).toFloat() }

/**
 * Everything the draw lambda needs, preallocated once per (lift, density): joint/prop/stage arrays, the sort order,
 * two fill Paths and the dash effect. [draw] allocates nothing (Offset/Color are value classes).
 */
private class FigureRenderer(val lift: DemoLift, val fit: DemoFit, val density: Float) {
    private val ang = FloatArray(10)
    private val x = FloatArray(Fk.N); private val y = FloatArray(Fk.N); private val z = FloatArray(Fk.N)
    private val gx = FloatArray(Fk.N); private val gy = FloatArray(Fk.N); private val gz = FloatArray(Fk.N)
    private val cSx = FloatArray(Fk.N); private val cSy = FloatArray(Fk.N); private val cDp = FloatArray(Fk.N)
    private val gSx = FloatArray(Fk.N); private val gSy = FloatArray(Fk.N); private val gDp = FloatArray(Fk.N)
    private val bDepth = FloatArray(N_BONES); private val order = IntArray(N_BONES)
    private val props = DEMO_PROPS[lift]?.toTypedArray() ?: emptyArray()
    private val pSx = FloatArray(props.size * 2); private val pSy = FloatArray(props.size * 2); private val pDp = FloatArray(props.size * 2)
    private val farFill = Path(); private val nearFill = Path(); private val planePath = Path()
    private val gridPath = Path(); private val ghostPath = Path(); private val capped = BooleanArray(Fk.N)
    private val lbX = FloatArray(2); private val lbY = FloatArray(2); private val lbD = FloatArray(2)
    private var gridAlpha = 0.15f; private var shift = 0f
    // Cached per renderer (never per frame): the dash effect and the Stroke styles built from it. The head stroke is rebuilt only when its width changes.
    private val dash = PathEffect.dashPathEffect(floatArrayOf(7f * density, 6f * density))
    private val hair = Stroke(density)
    private val dashStroke = Stroke(1.5f * density, cap = StrokeCap.Butt, pathEffect = dash)
    private val ghostStroke = Stroke(1.5f * density, cap = StrokeCap.Round)
    private var headW = -1f; private var headStroke = hair

    // stage cache: grid lines (2 pts each), then plane quad (4), then plumb line (2); rebuilt only when the camera/size changes
    private val stX = FloatArray(GRID_N * 4 + 6); private val stY = FloatArray(GRID_N * 4 + 6); private val stD = FloatArray(GRID_N * 4 + 6)
    private var kYaw = Float.NaN; private var kPitch = 0f; private var kW = 0f; private var kH = 0f; private var kCmp = false

    // per-frame camera
    private var cyaw = 1f; private var syaw = 0f; private var cpit = 1f; private var spit = 0f
    private var ortho = false; private var scale = 1f; private var ocx = 0f; private var ocy = 0f
    private val wcx = (fit.minX + fit.maxX) / 2; private val wcy = fit.maxY / 2
    val span = max(fit.maxX - fit.minX, fit.maxY + 2 * DemoFit.PAD)
    private val top = fit.maxY + 0.1f
    private val tol = COINCIDE_FRAC * BODY_H

    init { staticPose(lift, true, ang); demoSolve(lift, ang, gx, gy, gz) } // target pose never changes

    private fun proj(px: Float, py: Float, pz: Float, sx: FloatArray, sy: FloatArray, dp: FloatArray, i: Int, shift: Float) {
        val xx = px - wcx; val yy = py - wcy
        val x1 = xx * cyaw + pz * syaw
        val z1 = -xx * syaw + pz * cyaw
        val y2 = yy * cpit + z1 * spit
        val z2 = -yy * spit + z1 * cpit
        val d = max(z2 + DIST, MIN_DEPTH)
        val f = if (ortho) 1f else DIST / d
        sx[i] = ocx + x1 * f * scale + shift; sy[i] = ocy - y2 * f * scale; dp[i] = d
    }

    private fun fOf(d: Float) = if (ortho) 1f else DIST / d
    private fun shade(d: Float) = if (ortho) 1f else depthAlpha(d, DIST)
    private fun widthPx(d: Float) = (if (ortho) 3.4f else depthWidthDp(d, DIST)) * density

    private fun buildStage(w: Float, h: Float, cmp: Boolean, yaw: Float, pitch: Float) {
        val g = span * 0.5f; val cx = wcx
        var p = 0
        for (i in 0 until GRID_N) { val zz = -g + 2 * g * i / (GRID_N - 1); proj(cx - g, 0f, zz, stX, stY, stD, p, 0f); proj(cx + g, 0f, zz, stX, stY, stD, p + 1, 0f); p += 2 }
        for (i in 0 until GRID_N) { val xx = cx - g + 2 * g * i / (GRID_N - 1); proj(xx, 0f, -g, stX, stY, stD, p, 0f); proj(xx, 0f, g, stX, stY, stD, p + 1, 0f); p += 2 }
        // all grid lines in ONE path at one mean alpha (18 draw calls -> 1; the per-line edge fade is gone)
        gridPath.reset(); var aSum = 0f
        for (l in 0 until GRID_N * 2) {
            aSum += 0.22f * 0.65f * shade((stD[2 * l] + stD[2 * l + 1]) / 2)
            gridPath.moveTo(stX[2 * l], stY[2 * l]); gridPath.lineTo(stX[2 * l + 1], stY[2 * l + 1])
        }
        gridAlpha = aSum / (GRID_N * 2)
        proj(cx - g, 0f, 0f, stX, stY, stD, p, 0f); proj(cx + g, 0f, 0f, stX, stY, stD, p + 1, 0f)
        proj(cx + g, top, 0f, stX, stY, stD, p + 2, 0f); proj(cx - g, top, 0f, stX, stY, stD, p + 3, 0f)
        proj(cx, 0f, 0f, stX, stY, stD, p + 4, 0f); proj(cx, top, 0f, stX, stY, stD, p + 5, 0f)
        planePath.reset() // plane quad: depends only on the camera, so it lives with the cache
        planePath.moveTo(stX[p], stY[p]); planePath.lineTo(stX[p + 1], stY[p + 1])
        planePath.lineTo(stX[p + 2], stY[p + 2]); planePath.lineTo(stX[p + 3], stY[p + 3]); planePath.close()
        proj(cx, 0f, 0f, lbX, lbY, lbD, 0, -shift); proj(cx, 0f, 0f, lbX, lbY, lbD, 1, shift) // NOW / TARGET label anchors
        kYaw = yaw; kPitch = pitch; kW = w; kH = h; kCmp = cmp
    }

    fun draw(
        ds: DrawScope, phase: Float, yaw0: Float, pitch0: Float, flat: Boolean,
        ghostOn: Boolean, compare: Boolean, stage: Boolean, plane: Boolean,
        nowLbl: TextLayoutResult? = null, tgtLbl: TextLayoutResult? = null,
    ) = with(ds) {
        val w = size.width; val h = size.height
        val yaw = if (flat) (Math.PI / 2).toFloat() else yaw0
        val pitch = if (flat) 0f else pitch0
        cyaw = cos(yaw); syaw = sin(yaw); cpit = cos(pitch); spit = sin(pitch)
        ortho = flat
        // one bounding fit for figure, props and ghost: ~85% of the height, shrunk only if the width (x2 in compare) needs it
        val fw = fit.maxX - fit.minX
        scale = minOf((if (compare) 0.74f else 0.85f) * h / (fit.maxY + 2 * DemoFit.PAD), 0.9f * w / (fw * (if (compare) 2f else 1f)))
        ocx = w / 2; ocy = if (compare) h * 0.46f else h / 2
        val off = if (compare) 0.5f * fw * scale else 0f
        shift = off

        poseAt(lift, phase, ang); demoSolve(lift, ang, x, y, z)
        for (i in 0 until Fk.N) proj(x[i], y[i], z[i], cSx, cSy, cDp, i, -off)
        val ghost = ghostOn || compare
        if (ghost) for (i in 0 until Fk.N) proj(gx[i], gy[i], gz[i], gSx, gSy, gDp, i, off)

        // stage: grid + plumb plane + plumb line
        if (stage && !flat) {
            if (yaw != kYaw || pitch != kPitch || w != kW || h != kH || compare != kCmp) buildStage(w, h, compare, yaw, pitch)
            drawPath(gridPath, SahiColors.Dust.copy(alpha = gridAlpha), style = hair)
            val q = GRID_N * 4
            if (plane) {
                // face-on (SIDE) or edge-on (FRONT) reads cleanly; near 45 deg the quad slices through the body, so it nearly vanishes
                val diag = kotlin.math.abs(sin(2f * yaw))
                drawPath(planePath, SahiColors.Neel.copy(alpha = 0.06f * (1f - 0.5f * diag)))
                drawPath(planePath, SahiColors.Neel.copy(alpha = 0.18f - 0.13f * diag), style = hair)
            }
            drawLine(SahiColors.Dust.copy(alpha = 0.35f), Offset(stX[q + 4], stY[q + 4]), Offset(stX[q + 5], stY[q + 5]), density)
            if (compare && nowLbl != null && tgtLbl != null) { // anchors come from the stage cache (they only move with the camera)
                val ly = minOf(maxOf(lbY[0], lbY[1]) + 8f * density, h - nowLbl.size.height - 4f * density)
                drawText(nowLbl, topLeft = Offset(lbX[0] - nowLbl.size.width / 2f, ly))
                drawText(tgtLbl, topLeft = Offset(lbX[1] - tgtLbl.size.width / 2f, ly))
            }
        }

        // props (same DEMO_PROPS segments as the 2D demo, thin; plates are octagons so they project to ellipses)
        for (i in props.indices) {
            val p = props[i]
            proj(p.wx(0, x), p.wy(0, y), p.wz(0), pSx, pSy, pDp, 2 * i, -off)
            proj(p.wx(1, x), p.wy(1, y), p.wz(1), pSx, pSy, pDp, 2 * i + 1, -off)
            val d = (pDp[2 * i] + pDp[2 * i + 1]) / 2
            drawLine(SahiColors.Dust.copy(alpha = 0.45f * shade(d)), Offset(pSx[2 * i], pSy[2 * i]), Offset(pSx[2 * i + 1], pSy[2 * i + 1]), density, StrokeCap.Round)
        }

        // ghost. compare: the target as a second full thin Neel figure (bones + head, ONE path, no body fill) at 45% alpha, labelled below.
        // overlay: thin dashed bones (ONE dashed path) only where the target differs from the current pose.
        if (ghost) {
            ghostPath.reset()
            if (compare) {
                val gc = SahiColors.Neel.copy(alpha = 0.45f)
                val ghr = Fk.L_HEAD * 0.7f * fOf(gDp[Fk.HEAD]) * scale
                drawCircle(gc, ghr, Offset(gSx[Fk.HEAD], gSy[Fk.HEAD]), style = hair)
                for (b in 0 until N_BONES) {
                    val a = Fk.BONE_A[b]; val e = Fk.BONE_B[b]
                    var sx = gSx[a]; var sy = gSy[a]
                    if (b == 0) {
                        val dx = gSx[e] - sx; val dy = gSy[e] - sy; val len = sqrt(dx * dx + dy * dy)
                        if (len <= ghr) continue
                        sx += dx / len * ghr; sy += dy / len * ghr
                    }
                    ghostPath.moveTo(sx, sy); ghostPath.lineTo(gSx[e], gSy[e])
                }
                drawPath(ghostPath, gc, style = ghostStroke)
            } else {
                for (b in 0 until N_BONES) {
                    val a = Fk.BONE_A[b]; val e = Fk.BONE_B[b]
                    if (boneCoincides(x, y, z, gx, gy, gz, a, e, tol)) continue
                    ghostPath.moveTo(gSx[a], gSy[a]); ghostPath.lineTo(gSx[e], gSy[e])
                }
                drawPath(ghostPath, SahiColors.Neel.copy(alpha = 0.6f), style = dashStroke)
            }
        }

        // body fill: tapered capsules in two low-alpha paths (far / near of the pelvis) so front and back read.
        // Each joint gets ONE octagon cap (the first bone to reach it), not one per bone end: about half the contours.
        farFill.reset(); nearFill.reset(); capped.fill(false)
        val pelvisD = cDp[Fk.PELVIS]
        for (b in 0 until N_BONES) {
            val r0w = FILL_R0[b]; if (r0w <= 0f) continue
            val a = Fk.BONE_A[b]; val e = Fk.BONE_B[b]
            val ax = cSx[a]; val ay = cSy[a]; val ex = cSx[e]; val ey = cSy[e]
            val r0 = r0w * fOf(cDp[a]) * scale; val r1 = FILL_R1[b] * fOf(cDp[e]) * scale
            val path = if (b != 1 && (cDp[a] + cDp[e]) / 2 > pelvisD + 0.02f) farFill else nearFill
            val dx = ex - ax; val dy = ey - ay; val len = sqrt(dx * dx + dy * dy)
            if (len > 0.5f) { // clockwise quad; the octagons below wind the same way so overlaps never cancel
                val nx = -dy / len; val ny = dx / len
                path.moveTo(ax + nx * r0, ay + ny * r0); path.lineTo(ex + nx * r1, ey + ny * r1)
                path.lineTo(ex - nx * r1, ey - ny * r1); path.lineTo(ax - nx * r0, ay - ny * r0); path.close()
            }
            for (end in 0..1) {
                val j = if (end == 0) a else e
                if (capped[j]) continue
                capped[j] = true
                val cx = cSx[j]; val cy = cSy[j]; val r = if (end == 0) r0 else r1
                path.moveTo(cx + r * OCT_C[0], cy - r * OCT_S[0])
                for (k in 1 until 8) path.lineTo(cx + r * OCT_C[k], cy - r * OCT_S[k])
                path.close()
            }
        }
        val chalk = SahiColors.Chalk
        drawPath(farFill, chalk.copy(alpha = 0.07f))
        drawPath(nearFill, chalk.copy(alpha = 0.14f))

        // head: circle sized by depth
        val hd = cDp[Fk.HEAD]; val hr = Fk.L_HEAD * 0.7f * fOf(hd) * scale
        val ha = shade(hd)
        drawCircle(chalk.copy(alpha = 0.12f * ha), hr, Offset(cSx[Fk.HEAD], cSy[Fk.HEAD]))
        drawCircle(chalk.copy(alpha = ha), hr, Offset(cSx[Fk.HEAD], cSy[Fk.HEAD]), style = run { val hw = widthPx(hd) * 0.6f; if (kotlin.math.abs(hw - headW) > 0.05f) { headW = hw; headStroke = Stroke(hw) }; headStroke })

        // bones far -> near, each with a dot at its distal joint (every joint but the head is some bone's B end)
        for (b in 0 until N_BONES) bDepth[b] = (cDp[Fk.BONE_A[b]] + cDp[Fk.BONE_B[b]]) / 2
        sortFarToNear(bDepth, order, N_BONES)
        for (k in 0 until N_BONES) {
            val b = order[k]; val a = Fk.BONE_A[b]; val e = Fk.BONE_B[b]
            val d = bDepth[b]; val al = shade(d); val wp = widthPx(d); val c = chalk.copy(alpha = al)
            var sx = cSx[a]; var sy = cSy[a]
            if (b == 0) { // neck: start at the head circle's edge
                val dx = cSx[e] - sx; val dy = cSy[e] - sy; val len = sqrt(dx * dx + dy * dy)
                if (len <= hr) continue
                sx += dx / len * hr; sy += dy / len * hr
            }
            drawLine(c, Offset(sx, sy), Offset(cSx[e], cSy[e]), wp, StrokeCap.Round)
            val small = e == Fk.WRI || e == Fk.WRI + 1 || e == Fk.TOE || e == Fk.TOE + 1
            drawCircle(chalk.copy(alpha = shade(cDp[e])), wp * (if (small) 0.55f else 0.85f), Offset(cSx[e], cSy[e]))
        }
    }
}

/**
 * The FK figure in true perspective, orbitable by drag. [phase] (read in the draw lambda only) 0..1 is the playback
 * position (poseAt); the draw also reads the orbit angles, so nothing recomposes and nothing redraws while idle.
 * Body = Chalk bones and joint dots (width and alpha follow depth) over a faint tapered body fill. Stage (interactive
 * only) = perspective ground grid + plumb line, plus the plumb plane when [showPlane]. Ghost = thin dashed Neel
 * outline of the target pose: [showGhost] overlays it (coincident bones skipped), [compare] draws it alongside.
 * FULL: drag + inertia. REDUCED: drag, no inertia. OFF: flat 2D front view (orthographic, no depth shading, no stage).
 */
@Composable
fun PlumbFigure(
    lift: DemoLift, phase: () -> Float, showGhost: Boolean, modifier: Modifier = Modifier,
    orbitState: OrbitState? = null, showPlane: Boolean = false, interactive: Boolean = true, compare: Boolean = false,
) {
    val level = LocalMotion.current.level
    val flat = level == MotionLevel.OFF
    val orbit = orbitState ?: remember(lift, level) {
        if (flat) OrbitState(Math.PI.toFloat() / 2, 0f) else OrbitState(Math.PI.toFloat() / 4, ORBIT_DEFAULT_PITCH, ORBIT_MIN_PITCH, ORBIT_MAX_PITCH)
    }
    val density = LocalDensity.current.density
    val tm = rememberTextMeasurer()
    val lblStyle = TextStyle(fontSize = 10.sp, color = SahiColors.Dust)
    val nowLbl = remember(tm) { tm.measure("NOW", lblStyle) }
    val tgtLbl = remember(tm) { tm.measure("TARGET", lblStyle) }
    val r = remember(lift, density) { FigureRenderer(lift, demoFit(lift, DemoView.SIDE), density) }
    Canvas(if (interactive) modifier.orbitDrag(orbit) else modifier) {
        r.draw(this, phase(), orbit.yaw, orbit.pitch, flat, showGhost, compare, interactive, showPlane, nowLbl, tgtLbl)
    }
}
