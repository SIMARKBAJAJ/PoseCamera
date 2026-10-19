package com.example.posecamera.ui.design.g3d

/**
 * Pure helpers for the Plumb Figure draw loop (no allocation, unit-tested).
 *
 * Fills [order] with 0 until [n] and sorts it far-to-near by [depth] (larger depth first) with an insertion sort:
 * n <= 20 and nearly sorted frame to frame, so this beats any allocating sort. Stable for equal depths.
 */
fun sortFarToNear(depth: FloatArray, order: IntArray, n: Int) {
    for (i in 0 until n) order[i] = i
    for (i in 1 until n) {
        val v = order[i]; val d = depth[v]
        var j = i - 1
        while (j >= 0 && depth[order[j]] < d) { order[j + 1] = order[j]; j-- }
        order[j + 1] = v
    }
}

/** True when bone [a]-[b] of the ghost lies on the same bone of the figure: both ends within [tol] (world units, 3D). */
fun boneCoincides(
    cx: FloatArray, cy: FloatArray, cz: FloatArray, gx: FloatArray, gy: FloatArray, gz: FloatArray,
    a: Int, b: Int, tol: Float,
): Boolean {
    val t2 = tol * tol
    return near2(cx, cy, cz, gx, gy, gz, a) <= t2 && near2(cx, cy, cz, gx, gy, gz, b) <= t2
}

private fun near2(cx: FloatArray, cy: FloatArray, cz: FloatArray, gx: FloatArray, gy: FloatArray, gz: FloatArray, i: Int): Float {
    val dx = cx[i] - gx[i]; val dy = cy[i] - gy[i]; val dz = cz[i] - gz[i]
    return dx * dx + dy * dy + dz * dz
}

/** Near = 1, far = [FAR_ALPHA_MIN]: linear in depth over +-[DEPTH_RANGE] around the focus distance. */
const val FAR_ALPHA_MIN = 0.35f
const val DEPTH_RANGE = 0.6f

fun depthAlpha(depth: Float, dist: Float): Float {
    val t = ((depth - dist) / DEPTH_RANGE).coerceIn(-1f, 1f) // -1 near .. 1 far
    return FAR_ALPHA_MIN + (1f - FAR_ALPHA_MIN) * (1f - t) / 2f
}

/** Width in dp: perspective-scaled (near thicker), always inside [MIN_W_DP]..[MAX_W_DP]. */
const val MIN_W_DP = 2f
const val MAX_W_DP = 6f
fun depthWidthDp(depth: Float, dist: Float, baseDp: Float = 3.4f): Float {
    val f = dist / depth
    return (baseDp * f * f).coerceIn(MIN_W_DP, MAX_W_DP)
}

/** Playback step: [phase] advanced by [dtNanos] of a [cycleNanos]-long loop, wrapped into 0..1 (dt <= 0 or a bad cycle leaves it). */
fun advancePhase(phase: Float, dtNanos: Long, cycleNanos: Long): Float {
    if (dtNanos <= 0L || cycleNanos <= 0L) return phase
    val p = phase + dtNanos.toFloat() / cycleNanos
    return p - kotlin.math.floor(p)
}

/** Viewer playback is capped at ~30 fps (two draws per 60 Hz refresh saved; a slow demo loop does not need more). */
const val PLAY_FRAME_NANOS = 33_000_000L
