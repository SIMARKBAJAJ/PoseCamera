package com.example.posecamera.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.posecamera.ui.train.ScoreBand
import com.example.posecamera.ui.train.scoreBand

fun scoreColor(score: Int): Color = when (scoreBand(score)) {
    ScoreBand.STRONG -> SahiColors.Sahi
    ScoreBand.OK -> SahiColors.Haldi
    ScoreBand.NEEDS_WORK -> SahiColors.Sindoor
}

/** 0-100 arc; "—" and an empty track for null. Sweeps in only at FULL motion, otherwise static. */
@Composable
fun ScoreRing(score: Int?, size: Dp = 96.dp, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val target = (score ?: 0) / 100f
    val sweep by animateFloatAsState(target, if (motion.level == MotionLevel.FULL) motion.state() else snap(), label = "scoreRing")
    val color = score?.let(::scoreColor) ?: SahiColors.Dust
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = size.toPx() * 0.1f
            val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(SahiColors.Hairline, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            if (score != null) drawArc(color, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(score?.toString() ?: "—", style = SahiType.display.copy(color = if (score == null) SahiColors.Dust else SahiColors.Chalk))
    }
}
