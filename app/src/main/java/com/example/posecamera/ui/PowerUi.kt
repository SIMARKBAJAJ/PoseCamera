package com.example.posecamera.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.power.Consent
import com.example.posecamera.power.ConsentStore
import com.example.posecamera.power.PowerMonitor
import com.example.posecamera.power.PowerPolicy
import com.example.posecamera.power.Tier
import com.example.posecamera.power.THERMAL_MODERATE
import com.example.posecamera.power.choosePolicy
import com.example.posecamera.power.effectiveAnimationScale
import com.example.posecamera.power.mustDegrade
import com.example.posecamera.power.needsPrompt
import com.example.posecamera.power.tierOf

/** The one place animation durations get scaled (power policy and system reduce-motion). */
fun <T> animSpec(scale: Float, durationMillis: Int = 300): FiniteAnimationSpec<T> =
    if (scale <= 0f) snap() else tween((durationMillis * scale).toInt())

class PowerUi(
    val policy: PowerPolicy,
    val animationScale: Float,
    /** Non-null while the user should be asked (consent) or told (forced thermal degrade). */
    val banner: String?,
    val askConsent: Boolean,
    /** Raw power tier (no consent applied): drives the motion level. */
    val tier: Tier,
    /** Closes the banner for this tier until the tier changes (this process only). */
    val dismiss: () -> Unit,
    val answer: (Boolean) -> Unit,
)

private var dismissedTier by mutableStateOf<Tier?>(null)

/** Raw battery/thermal tier as users see it: never NORMAL while a saver tier is active, whatever the consent. */
fun tierLabel(t: Tier): String = t.name

/** Second line under the tier: what the consent policy does to the camera. */
fun cameraQualityLabel(p: PowerPolicy): String = "Camera quality: " + if (p == PowerPolicy.NORMAL) "full" else "reduced"

/** Set once at the app root so every screen (shell banner, camera, You tab, motion) shares one consent state. */
val LocalPowerUi = androidx.compose.runtime.staticCompositionLocalOf<PowerUi?> { null }

@Composable
fun rememberPowerUi(): PowerUi = LocalPowerUi.current ?: rememberPowerUiRoot()

@Composable
fun rememberPowerUiRoot(): PowerUi {
    val context = LocalContext.current
    val monitor = remember(context) { PowerMonitor(context) }
    val store = remember(context) { ConsentStore(context) }
    val consents = remember(store) { mutableStateMapOf<Tier, Consent>().apply { Tier.entries.forEach { put(it, store.get(it)) } } }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }
    val state by monitor.state.collectAsStateWithLifecycle()
    val tier = tierOf(state)
    val consent = consents[tier] ?: Consent.UNDECIDED
    val policy = choosePolicy(state, consent)
    val systemScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val ask = needsPrompt(state, consent)
    val hot = state.thermal >= THERMAL_MODERATE
    val banner = when {
        tier == dismissedTier -> null
        mustDegrade(state) -> "Overheating: motion and camera reduced"
        ask && hot -> "Phone is hot: reduce motion?"
        ask -> "Battery ${state.batteryPercent}%: reduce motion?"
        else -> null
    }
    return PowerUi(policy, effectiveAnimationScale(policy.animationScale, systemScale), banner, ask, tier, { dismissedTier = tier }) { reduce ->
        val choice = if (reduce) Consent.REDUCE else Consent.KEEP
        consents[tier] = choice
        store.set(tier, choice)
    }
}

/** One compact 40 dp strip: text, Reduce / Keep while a choice is pending, close. */
@Composable
fun PowerBanner(ui: PowerUi, modifier: Modifier = Modifier) {
    val text = ui.banner ?: return
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = visible, enter = fadeIn(animSpec(ui.animationScale)), modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp)).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (ui.askConsent) {
                BannerAction("Reduce", Color.White) { ui.answer(true) }
                BannerAction("Keep", Color.White.copy(alpha = 0.7f)) { ui.answer(false) }
            }
            BannerAction("X", Color.White.copy(alpha = 0.7f), Modifier.width(40.dp), ui.dismiss)
        }
    }
}

@Composable
private fun BannerAction(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 10.dp).wrapContentHeight(Alignment.CenterVertically),
        color = color, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
    )
}
