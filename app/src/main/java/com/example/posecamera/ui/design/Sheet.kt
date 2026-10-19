package com.example.posecamera.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** AppShell hides its tab bar while true is reported here (sheets). No-op outside the shell. */
val LocalHideTabBar = staticCompositionLocalOf<(Boolean) -> Unit> { { } }

/** Bottom sheet in the Sahi look: dim scrim (tap or Back dismisses), chamfered Slate panel, lifts above the keyboard. */
@Composable
fun SahiSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val hideTabBar = LocalHideTabBar.current
    DisposableEffect(Unit) { hideTabBar(true); onDispose { hideTabBar(false) } } // the sheet draws over the whole screen, tab bar included
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().heightIn(max = 600.dp)
                .clip(ChamferShape()).background(SahiColors.Slate).clickable(remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SahiChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 48 dp hit box around the visible pill
    Box(modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            text, Modifier.clip(CircleShape).background(if (selected) SahiColors.Haldi else SahiColors.Slate2).padding(horizontal = 14.dp, vertical = 8.dp),
            style = SahiType.label.copy(color = if (selected) SahiColors.Ink else SahiColors.Chalk),
        )
    }
}

/** Big "- value +" control. */
@Composable
fun SahiStepper(value: String, unit: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepCircle("-", onMinus)
        Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = SahiType.display.copy(color = SahiColors.Chalk))
            Text(unit, style = SahiType.micro.copy(color = SahiColors.Dust))
        }
        StepCircle("+", onPlus)
    }
}

@Composable
private fun StepCircle(label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(SahiColors.Slate2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = SahiType.title.copy(color = SahiColors.Haldi))
    }
}
