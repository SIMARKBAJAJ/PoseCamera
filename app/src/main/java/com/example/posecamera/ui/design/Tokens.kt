package com.example.posecamera.ui.design

import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Brief section 4. Sahi (green) means correct form ONLY; Haldi never does. */
object SahiColors {
    val Ink = Color(0xFF0A0B0C)
    val Slate = Color(0xFF14171A)
    val Slate2 = Color(0xFF1D2125)
    val Hairline = Color(0xFF2C3237)
    val Chalk = Color(0xFFF3EEE4)
    val Dust = Color(0xFF9AA2A8)
    val Haldi = Color(0xFFFFB81C)
    val Sahi = Color(0xFF3FE0A0)
    val Sindoor = Color(0xFFFF4A3A)
    val Neel = Color(0xFF5AA9FF)
    val Mehendi = Color(0xFFB8D94A)
}

private val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))
private const val TNUM = "tnum"

/** Brief section 5. */
object SahiType {
    val hero = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Black, fontSize = 72.sp, lineHeight = 72.sp, fontFeatureSettings = TNUM)
    val display = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, fontFeatureSettings = TNUM)
    val title = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val label = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp)
    val micro = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = TNUM)
}
