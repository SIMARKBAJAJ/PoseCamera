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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.posecamera.camera.CameraSession
import com.example.posecamera.network.ApiResult
import com.example.posecamera.network.SahiRepRepository
import com.example.posecamera.pose.ExerciseConfig
import com.example.posecamera.pose.PoseEngine
import com.example.posecamera.pose.PoseFrame
import com.example.posecamera.pose.PushUpExerciseConfig
import com.example.posecamera.pose.RepCounter
import com.example.posecamera.pose.RepSetSummary
import com.example.posecamera.pose.SquatExerciseConfig
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

@Composable
fun PoseCameraApp() {
    val context = LocalContext.current
    val repository = remember(context) { SahiRepRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    var authenticated by remember { mutableStateOf(repository.hasSession()) }
    var authLoading by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    if (!authenticated) {
        AuthScreen(
            isLoading = authLoading,
            errorMessage = authError,
            onSubmit = { mode, username, password ->
                coroutineScope.launch {
                    authLoading = true
                    val result: ApiResult<Unit> = when (mode) {
                        AuthMode.LOGIN -> repository.login(username, password)
                        AuthMode.REGISTER -> when (val registration = repository.register(username, password)) {
                            is ApiResult.Success -> repository.login(username, password)
                            is ApiResult.Failure -> registration
                        }
                    }
                    when (result) {
                        is ApiResult.Success -> authenticated = true
                        is ApiResult.Failure -> authError = result.message
                    }
                    authLoading = false
                }
            },
            onClearError = { authError = null },
        )
        return
    }

    CameraPermissionGate(
        repository = repository,
        onSessionExpired = { message ->
            authError = message
            authenticated = false
        },
    )
}

@Composable
private fun CameraPermissionGate(
    repository: SahiRepRepository,
    onSessionExpired: (String) -> Unit,
) {
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
        CameraContent(repository, onSessionExpired)
    } else {
        PermissionContent { launcher.launch(Manifest.permission.CAMERA) }
    }
}

@Composable
private fun CameraContent(
    repository: SahiRepRepository,
    onSessionExpired: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var frame by remember { mutableStateOf<PoseFrame?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<RepSetSummary?>(null) }
    var activeConfig by remember { mutableStateOf<ExerciseConfig>(SquatExerciseConfig) }
    var repCounter by remember { mutableStateOf(RepCounter(activeConfig)) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val poseEngine = remember(context, executor) {
        PoseEngine(
            context,
            executor,
            {
                if (it.analysis.exerciseType == activeConfig.type) {
                    frame = it
                    if (summary == null) repCounter.onFrame(it.analysis, it.timestampMillis)
                }
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
            uploadSet = repository::submitSet,
            loadHistory = repository::getHistory,
            onSessionExpired = onSessionExpired,
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp),
        ) {
            ExerciseSelector(
                activeConfig = activeConfig,
                onSelected = { config ->
                    if (config.type != activeConfig.type) {
                        poseEngine.setActiveConfig(config)
                        activeConfig = config
                        repCounter = RepCounter(config)
                        frame = null
                        error = null
                    }
                },
            )
            activeConfig.guidance?.let { guidance ->
                Text(
                    text = guidance,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
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
private fun ExerciseSelector(
    activeConfig: ExerciseConfig,
    onSelected: (ExerciseConfig) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(8.dp))
            .selectableGroup(),
    ) {
        listOf(SquatExerciseConfig, PushUpExerciseConfig).forEach { config ->
            val selected = config.type == activeConfig.type
            Text(
                text = config.type.displayLabel,
                color = if (selected) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(
                        color = if (selected) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .selectable(
                        selected = selected,
                        onClick = { onSelected(config) },
                        role = Role.RadioButton,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
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
