package com.example.posecamera.power

import kotlin.math.min

// All thresholds below are product defaults, not evidence-based. Tune on real devices.
const val SAVER_BATTERY_PERCENT = 30 // below this (and not charging) -> SAVER
const val CRITICAL_BATTERY_PERCENT = 15 // at or below this (and not charging) -> CRITICAL

// Values match PowerManager.THERMAL_STATUS_*.
const val THERMAL_LIGHT = 1
const val THERMAL_MODERATE = 2 // -> SAVER
const val THERMAL_SEVERE = 3 // -> CRITICAL, and degrades without asking

// Battery-temperature fallback (tenths of a degree C) when thermal status is unavailable (API < 29).
const val TEMP_LIGHT_TENTHS = 400
const val TEMP_MODERATE_TENTHS = 420
const val TEMP_SEVERE_TENTHS = 450

data class PowerState(
    val batteryPercent: Int,
    val charging: Boolean,
    val batterySaver: Boolean,
    val thermal: Int, // 0..6, PowerManager.THERMAL_STATUS_*
)

enum class Tier { NORMAL, SAVER, CRITICAL }

enum class Consent { UNDECIDED, REDUCE, KEEP }

enum class OverlayDetail { FULL, MINIMAL }

// Hint only: lite is the single model shipped; a heavier variant would be a separate, explicit decision.
enum class ModelVariant { LITE }

data class Resolution(val width: Int, val height: Int)

data class PowerPolicy(
    val analysisFpsCap: Int,
    val analysisResolution: Resolution,
    val modelVariant: ModelVariant,
    val useGpuDelegate: Boolean,
    val animationScale: Float,
    val overlayDetail: OverlayDetail,
    val reduceScaling: Boolean, // overlay maps each landmark to screen once per frame
) {
    companion object {
        val NORMAL = PowerPolicy(30, Resolution(640, 480), ModelVariant.LITE, true, 1f, OverlayDetail.FULL, false)
        val SAVER = PowerPolicy(15, Resolution(480, 360), ModelVariant.LITE, true, 0.5f, OverlayDetail.MINIMAL, true)
        val CRITICAL = PowerPolicy(10, Resolution(320, 240), ModelVariant.LITE, false, 0f, OverlayDetail.MINIMAL, true)
    }
}

fun tierOf(s: PowerState): Tier = when {
    s.thermal >= THERMAL_SEVERE -> Tier.CRITICAL
    !s.charging && s.batteryPercent <= CRITICAL_BATTERY_PERCENT -> Tier.CRITICAL
    s.thermal >= THERMAL_MODERATE || s.batterySaver ||
        (!s.charging && s.batteryPercent < SAVER_BATTERY_PERCENT) -> Tier.SAVER
    else -> Tier.NORMAL
}

/** Thermal SEVERE or worse degrades without asking, to protect the device. */
fun mustDegrade(s: PowerState): Boolean = s.thermal >= THERMAL_SEVERE

/** True when the user should be asked once about the current tier. */
fun needsPrompt(s: PowerState, consent: Consent): Boolean =
    tierOf(s) != Tier.NORMAL && !mustDegrade(s) && consent == Consent.UNDECIDED

/** [userChoice] is the remembered consent for the current tier. Pure; no silent degradation except thermal. */
fun choosePolicy(state: PowerState, userChoice: Consent): PowerPolicy {
    val tier = tierOf(state)
    if (!mustDegrade(state) && userChoice != Consent.REDUCE) return PowerPolicy.NORMAL
    return when (tier) {
        Tier.NORMAL -> PowerPolicy.NORMAL
        Tier.SAVER -> PowerPolicy.SAVER
        Tier.CRITICAL -> PowerPolicy.CRITICAL
    }
}

fun thermalFromTemperature(tenthsC: Int): Int = when {
    tenthsC >= TEMP_SEVERE_TENTHS -> THERMAL_SEVERE
    tenthsC >= TEMP_MODERATE_TENTHS -> THERMAL_MODERATE
    tenthsC >= TEMP_LIGHT_TENTHS -> THERMAL_LIGHT
    else -> 0
}

/** [apiStatus] is PowerManager.getCurrentThermalStatus (API 29+), null when unavailable. */
fun resolveThermal(apiStatus: Int?, batteryTempTenthsC: Int): Int =
    apiStatus ?: thermalFromTemperature(batteryTempTenthsC)

/** System scale 0 (reduce motion) wins; a slower system scale never speeds ours up. */
fun effectiveAnimationScale(policyScale: Float, systemScale: Float): Float = min(policyScale, systemScale)

/** Drops frames beyond a fps cap. Allocation-free; call [allow] from the analysis thread only. */
class FrameThrottle(fps: Int = PowerPolicy.NORMAL.analysisFpsCap) {
    @Volatile private var minIntervalMs = 0L
    private var lastMillis = Long.MIN_VALUE / 2

    init {
        setCap(fps)
    }

    fun setCap(fps: Int) {
        // 3/4 of the ideal interval so camera timestamp jitter doesn't halve the rate.
        minIntervalMs = if (fps <= 0) 0L else 1000L / fps * 3 / 4
    }

    fun allow(nowMillis: Long): Boolean {
        if (nowMillis - lastMillis < minIntervalMs) return false
        lastMillis = nowMillis
        return true
    }
}
