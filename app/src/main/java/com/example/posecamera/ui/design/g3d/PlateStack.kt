package com.example.posecamera.ui.design.g3d

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.posecamera.ui.design.SahiColors

private class Plate(val kg: Float, val color: Color, val diameterMm: Float, val thicknessMm: Float)

// IWF-style colours; diameters are approximate (big plates 450 mm, change plates smaller).
private val PLATES = listOf(
    Plate(25f, Color(0xFFD93A2B), 450f, 60f), Plate(20f, Color(0xFF2F6FD6), 450f, 50f),
    Plate(15f, Color(0xFFF2C32C), 400f, 40f), Plate(10f, Color(0xFF2FA866), 325f, 32f),
    Plate(5f, Color(0xFFF3EEE4), 280f, 26f), Plate(2.5f, Color(0xFF9AA2A8), 230f, 20f),
    Plate(1.25f, Color(0xFF6A7278), 190f, 16f),
)

/** Plates for ONE side of the bar, heaviest first (greedy). Empty when [kg] does not exceed [bar]. */
fun plateBreakdown(kg: Float, bar: Float = 20f): List<Float> {
    var rest = (kg - bar) / 2f
    val out = ArrayList<Float>()
    for (p in PLATES) while (rest >= p.kg - 1e-4f) { out += p.kg; rest -= p.kg }
    return out
}

/** Side-on barbell with the same plates on both ends. */
@Composable
fun PlateStack(kg: Float, barKg: Float = 20f, modifier: Modifier = Modifier) {
    val plates = plateBreakdown(kg, barKg).map { k -> PLATES.first { it.kg == k } }
    Canvas(modifier) {
        val mm = size.height / 500f // tallest plate (450) fills 90% of the height
        val cy = size.height / 2
        val barH = 20f * mm
        drawRect(SahiColors.Dust, Offset(0f, cy - barH / 2), Size(size.width, barH))
        val gap = 2f * mm
        for (dir in intArrayOf(1, -1)) {
            var x = if (dir == 1) size.width * 0.72f else size.width * 0.28f
            for (p in plates) {
                val w = p.thicknessMm * mm; val h = p.diameterMm * mm
                val left = if (dir == 1) x else x - w
                drawRoundRect(p.color, Offset(left, cy - h / 2), Size(w, h), CornerRadius(w / 2))
                x += dir * (w + gap)
            }
        }
    }
}
