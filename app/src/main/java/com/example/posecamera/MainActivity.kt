package com.example.posecamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.posecamera.ui.PoseCameraApp
import com.example.posecamera.ui.PoseCameraTheme
import com.example.posecamera.ui.design.SahiColors
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ink = SahiColors.Ink.toArgb()
        // SystemBarStyle.dark = Ink background with light icons.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(ink), navigationBarStyle = SystemBarStyle.dark(ink))
        // Debug-only screenshot hook: adb shell am start -n .../.MainActivity --es route home|train|library|progress|you|gallery
        val route = if (BuildConfig.DEBUG) intent?.getStringExtra("route") else null
        // Debug-only sample data: adb shell am start -n .../.MainActivity --ez seed true [--ez seed_force true]. Never runs in release.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("seed", false) == true) {
            val force = intent.getBooleanExtra("seed_force", false)
            lifecycleScope.launch { seedDebugData(applicationContext, force) }
        }
        setContent {
            PoseCameraTheme { PoseCameraApp(route) }
        }
    }
}
