package com.example.posecamera.ui.design

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.posecamera.power.Tier
import com.example.posecamera.ui.rememberPowerUi

enum class MotionLevel { FULL, REDUCED, OFF }

/** Pure mapping: policy scale 1 = NORMAL, 0.5 = SAVER, 0 = CRITICAL; system "remove animations" (0) forces OFF. */
fun motionLevelOf(policyScale: Float, systemScale: Float): MotionLevel = when {
    systemScale <= 0f || policyScale <= 0f -> MotionLevel.OFF
    policyScale < 1f -> MotionLevel.REDUCED
    else -> MotionLevel.FULL
}

/** Pure: raw power tier (consent not involved) -> motion level; system "remove animations" (0) forces OFF. */
fun motionLevelOfTier(tier: Tier, systemScale: Float): MotionLevel = when {
    systemScale <= 0f -> MotionLevel.OFF
    tier == Tier.CRITICAL -> MotionLevel.OFF
    tier == Tier.SAVER -> MotionLevel.REDUCED
    else -> MotionLevel.FULL
}

/** Brief section 6. Every spec degrades: REDUCED -> tween(150), OFF -> snap(). */
class MotionSpec(val level: MotionLevel) {
    val allowIdleLoops get() = level == MotionLevel.FULL
    val allow3dAutoMotion get() = level == MotionLevel.FULL
    val allowGlow get() = level == MotionLevel.FULL
    val maxParticles get() = if (level == MotionLevel.FULL) 24 else 0

    private fun <T> pick(full: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = when (level) {
        MotionLevel.FULL -> full
        MotionLevel.REDUCED -> tween(150)
        MotionLevel.OFF -> snap()
    }

    fun <T> snapSpring(): FiniteAnimationSpec<T> = pick(spring(dampingRatio = 0.55f, stiffness = 380f))
    fun <T> layoutSpring(): FiniteAnimationSpec<T> = pick(spring(dampingRatio = 0.8f, stiffness = 200f))
    fun <T> micro(): FiniteAnimationSpec<T> = pick(tween(120))
    fun <T> state(): FiniteAnimationSpec<T> = pick(tween(240))
    fun <T> ping(): FiniteAnimationSpec<T> = pick(tween(400))
    fun <T> screen(): FiniteAnimationSpec<T> = pick(tween(370))
}

val LocalMotion = compositionLocalOf { MotionSpec(MotionLevel.FULL) }

@Composable
fun rememberMotionSpec(ui: com.example.posecamera.ui.PowerUi = rememberPowerUi()): MotionSpec {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var system by remember(resolver) { mutableFloatStateOf(read()) }
    DisposableEffect(resolver) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { system = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, obs)
        system = read() // may have changed between composition and registration
        onDispose { resolver.unregisterContentObserver(obs) }
    }
    val level = motionLevelOfTier(ui.tier, system)
    return remember(level) { MotionSpec(level) }
}
