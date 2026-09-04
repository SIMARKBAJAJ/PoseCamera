package com.example.posecamera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.posecamera.camera.CameraSession
import com.example.posecamera.pose.PoseEngine
import com.example.posecamera.pose.PoseFrame
import com.example.posecamera.pose.RepCounter
import com.example.posecamera.pose.RepSetSummary
import java.util.Locale
import java.util.concurrent.Executors

@Composable
fun PoseCameraApp() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var requested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (granted) {
        CameraContent()
    } else {
        PermissionContent { launcher.launch(Manifest.permission.CAMERA) }
    }
}

@Composable
private fun CameraContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var frame by remember { mutableStateOf<PoseFrame?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<RepSetSummary?>(null) }
    val repCounter = remember { RepCounter() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val poseEngine = remember(context, executor) {
        PoseEngine(
            context,
            executor,
            {
                frame = it
                if (summary == null) repCounter.onFrame(it.squatForm, it.timestampMillis)
            },
            { error = it },
        )
    }
    val session = remember(context, lifecycleOwner, executor, poseEngine) {
        CameraSession(context, lifecycleOwner, executor, poseEngine) { error = it }
    }

    DisposableEffect(session, poseEngine) {
        poseEngine.initialize()
        onDispose {
            session.close()
            poseEngine.close()
        }
    }

    val currentSummary = summary
    if (currentSummary != null) {
        SetSummaryScreen(
            summary = currentSummary,
            onStartNewSet = {
                summary = null
                frame = null
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    session.attach(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        PoseOverlay(frame)
        Text(
            text = String.format(Locale.US, "%.1f FPS", frame?.fps ?: 0f),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
        Button(
            onClick = {
                session.close()
                summary = repCounter.endSet()
                frame = null
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            Text("End set")
        }
        IconButton(
            onClick = {
                frame = null
                session.toggleLens()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp)
                .background(Color.Black.copy(alpha = 0.62f), CircleShape)
                .semantics { contentDescription = "Switch camera" },
        ) {
            Text("↻", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        }
        error?.let {
            Text(
                text = it,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun PermissionContent(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF111111)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Camera access is required to detect your pose.", color = Color.White)
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text("Grant camera access")
        }
    }
}
