package com.example.posecamera.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.posecamera.demo.DemoLift
import com.example.posecamera.ui.design.g3d.MuscleMap
import com.example.posecamera.ui.design.g3d.MuscleSide
import com.example.posecamera.ui.design.g3d.PlateStack
import com.example.posecamera.ui.design.g3d.PlumbFigure
import androidx.compose.runtime.withFrameNanos

/** Every design component on one scrollable page. Not wired to navigation; call from a debug entry point. */
@Composable
fun DesignGallery() {
    val motion = rememberMotionSpec()
    var rep by remember { mutableFloatStateOf(0.3f) }
    var side by remember { mutableStateOf(MuscleSide.FRONT) }
    val phase = remember { mutableFloatStateOf(0f) } // read only inside PlumbFigure's draw lambda: no gallery recomposition
    var swing by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motion.level) { // demo driver: ring/gauge/plumb wiggle every 2 s; figure plays only when idle loops are allowed
        var t = 0
        while (true) {
            rep = if (t % 2 == 0) 0.85f else 0.3f; swing = if (t % 2 == 0) 0.8f else 0f; t++
            if (motion.allowIdleLoops) {
                val start = withFrameNanos { it }
                var now = start
                while (now - start < 2_000_000_000L) { now = withFrameNanos { it }; phase.floatValue = ((now - start) / 2_000_000_000f) % 1f }
            } else kotlinx.coroutines.delay(2000)
        }
    }
    CompositionLocalProvider(LocalMotion provides motion) {
        Column(
            Modifier.fillMaxSize().background(SahiColors.Ink).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DESIGN GALLERY  (${motion.level})", style = SahiType.label.copy(color = SahiColors.Dust))
            ChamferCard(Modifier.fillMaxWidth()) {
                Text("Today's plan", style = SahiType.title.copy(color = SahiColors.Chalk))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                Text("Squat 5 x 5 @ 80 kg", style = SahiType.body.copy(color = SahiColors.Dust))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SahiButton("Ek aur", onClick = { rep = (rep + 0.2f) % 1.2f })
                SyncPill()
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncPillState.entries.forEach { SyncPillView(it) }
            }
            StatBlock(1280f, "kg this week")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RepRing(rep)
                DepthGauge(rep)
                PlumbLine(swing)
                JointPing(trigger = (rep * 100).toInt() + 1, color = SahiColors.Haldi, modifier = Modifier.size(48.dp))
            }
            PlumbFigure(DemoLift.SQUAT, { phase.floatValue }, showGhost = true, modifier = Modifier.fillMaxWidth().height(360.dp))
            SahiButton("Flip muscle map", onClick = {
                side = if (side == MuscleSide.FRONT) MuscleSide.BACK else MuscleSide.FRONT
            })
            val heat = mapOf("chest" to 0.9f, "quads" to 0.7f, "abs" to 0.3f, "lats" to 0.8f, "glutes" to 0.6f, "traps" to 0.4f)
            MuscleMap(heat, side, Modifier.fillMaxWidth().height(320.dp))
            PlateStack(142.5f, modifier = Modifier.fillMaxWidth().height(120.dp))
        }
    }
}
