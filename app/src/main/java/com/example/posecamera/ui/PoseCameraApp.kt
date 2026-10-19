package com.example.posecamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.rememberMotionSpec
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.posecamera.camera.CameraSession
import com.example.posecamera.lifts.FORM_DISCLAIMER
import com.example.posecamera.lifts.LiftAnalysis
import com.example.posecamera.lifts.LiftConfig
import com.example.posecamera.lifts.LiftSession
import com.example.posecamera.lifts.Lifts
import com.example.posecamera.network.ApiResult
import com.example.posecamera.network.SahiRepRepository
import com.example.posecamera.pose.ExerciseConfig
import com.example.posecamera.pose.ExerciseType
import com.example.posecamera.pose.PoseEngine
import com.example.posecamera.pose.PoseFrame
import com.example.posecamera.pose.PushUpExerciseConfig
import com.example.posecamera.pose.RepCounter
import com.example.posecamera.pose.RepSetSummary
import com.example.posecamera.pose.SquatExerciseConfig
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.sync.syncLabel
import com.example.posecamera.ui.train.FormHud
import com.example.posecamera.ui.train.buildHud

@Composable
fun PoseCameraApp(initialRoute: String? = null) {
    val power = rememberPowerUiRoot()
    CompositionLocalProvider(LocalPowerUi provides power, LocalMotion provides rememberMotionSpec(power)) { PoseCameraAppContent(initialRoute) }
}

@Composable
private fun PoseCameraAppContent(initialRoute: String?) {
    if (initialRoute.equals("gallery", ignoreCase = true)) { // debug screenshot hook; MainActivity only passes it in DEBUG builds
        com.example.posecamera.ui.design.DesignGallery()
        return
    }
    val context = LocalContext.current
    val repository = remember(context) { SahiRepRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    var authenticated by remember { mutableStateOf(repository.hasSession()) }
    var authLoading by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var pendingSwitch by remember { mutableStateOf<Pair<String, String>?>(null) }

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
                        is ApiResult.Failure ->
                            if (result.needsDiscardConfirm) pendingSwitch = username to password
                            else authError = result.message
                    }
                    authLoading = false
                }
            },
            onClearError = { authError = null },
        )
        pendingSwitch?.let { (username, password) ->
            AlertDialog(
                onDismissRequest = { pendingSwitch = null },
                title = { Text("Switch account?") },
                text = { Text("Unsynced data from another account will be deleted") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingSwitch = null
                        coroutineScope.launch {
                            authLoading = true
                            when (val r = repository.login(username, password, discardUnsynced = true)) {
                                is ApiResult.Success -> authenticated = true
                                is ApiResult.Failure -> authError = r.message
                            }
                            authLoading = false
                        }
                    }) { Text("Discard") }
                },
                dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("Cancel") } },
            )
        }
        return
    }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { repository.startSync() } }
    AppShell(repository.local, initialRoute, onLogout = { repository.logout(); authError = null; authenticated = false }) { onDemos, startType ->
        CameraPermissionGate(
            repository = repository,
            onDemos = onDemos,
            startType = startType,
            onSessionExpired = { message ->
                authError = message
                authenticated = false
            },
        )
    }
}

@Composable
private fun CameraPermissionGate(
    repository: SahiRepRepository,
    onSessionExpired: (String) -> Unit,
    onDemos: (Boolean) -> Unit,
    startType: String?,
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
        CameraContent(repository, onSessionExpired, onDemos, startType)
    } else {
        PermissionContent { launcher.launch(Manifest.permission.CAMERA) }
    }
}

@Composable
private fun CameraContent(
    repository: SahiRepRepository,
    onSessionExpired: (String) -> Unit,
    onDemos: (Boolean) -> Unit,
    startType: String?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var frame by remember { mutableStateOf<PoseFrame?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<RepSetSummary?>(null) }
    // startType: Train's camera-only entries (Squat / Push-up) pre-select their exercise; null = Squat as before
    val startConfig = remember { if (startType == "PUSH_UP") PushUpExerciseConfig else SquatExerciseConfig }
    var activeConfig by remember { mutableStateOf<ExerciseConfig>(startConfig) }
    var repCounter by remember { mutableStateOf(RepCounter(activeConfig)) }
    var liftSession by remember { mutableStateOf<LiftSession?>(null) }
    val prefs = remember(context) { context.getSharedPreferences("form_prefs", Context.MODE_PRIVATE) }
    var disclaimerAck by remember { mutableStateOf(prefs.getBoolean(DISCLAIMER_KEY, false)) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val poseEngine = remember(context, executor) {
        PoseEngine(
            context,
            executor,
            {
                if (it.analysis.exerciseType == activeConfig.type) {
                    val lift = liftSession
                    if (lift != null && summary == null && disclaimerAck) {
                        frame = it.copy(analysis = lift.onFrame(it))
                    } else {
                        frame = it
                        if (summary == null && disclaimerAck) repCounter.onFrame(it.analysis, it.timestampMillis)
                    }
                }
            },
            { error = it },
            startConfig,
        )
    }
    val session = remember(context, lifecycleOwner, executor, poseEngine) {
        CameraSession(context, lifecycleOwner, executor, poseEngine) { error = it }
    }

    val syncUi by repository.syncStatus.collectAsStateWithLifecycle()
    LaunchedEffect(syncUi.needsLogin) {
        if (syncUi.needsLogin) onSessionExpired("Please log in again to sync")
    }

    val power = rememberPowerUi()
    val policy = power.policy

    DisposableEffect(session, poseEngine) {
        poseEngine.setPolicy(policy) // before initialize() so the first landmarker uses the right delegate
        poseEngine.initialize()
        onDispose {
            session.close()
            poseEngine.close()
        }
    }

    LaunchedEffect(session, poseEngine, policy) {
        session.applyPolicy(policy)
        poseEngine.setPolicy(policy)
    }

    var showDemos by remember { mutableStateOf(false) }
    // Tell the Train screen so it hides its CLOSE pill while Demos (which has its own Back) is open.
    LaunchedEffect(showDemos) { onDemos(showDemos) }
    DisposableEffect(Unit) { onDispose { onDemos(false) } }
    if (showDemos) {
        com.example.posecamera.demo.DemoScreen(onBack = { showDemos = false }, animationScale = power.animationScale)
        return
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
        PoseOverlay(frame, detail = policy.overlayDetail, reduceScaling = policy.reduceScaling)
        FormHud(buildHud(frame?.analysis, activeConfig, if (frame?.analysis is LiftAnalysis) null else repCounter.progress()))
        PowerBanner(
            power,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 96.dp),
        )
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
                        liftSession = (config as? LiftConfig)?.let(::LiftSession)
                        frame = null
                        error = null
                    }
                },
            )
            Text(
                text = String.format(Locale.US, "%.1f FPS", frame?.fps ?: 0f),
                style = SahiType.micro.copy(color = SahiColors.Chalk),
                modifier = Modifier.padding(top = 8.dp).hudBacking().padding(horizontal = 10.dp, vertical = 7.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                HudChip("Demos", true) { showDemos = true }
                HudChip(syncLabel(syncUi), !syncUi.running, repository::syncNow)
            }
            activeConfig.guidance?.let { guidance ->
                Text(
                    text = guidance,
                    style = SahiType.micro.copy(color = SahiColors.Chalk),
                    modifier = Modifier.padding(top = 8.dp).hudBacking().padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
        Text(
            "End set",
            style = SahiType.label.copy(color = SahiColors.Chalk),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(20.dp)
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(SahiColors.Slate.copy(alpha = 0.85f))
                .clickable {
                    session.close()
                    summary = liftSession?.endSet() ?: repCounter.endSet()
                    frame = null
                }
                .padding(horizontal = 20.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
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
        if (!disclaimerAck) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Before you start") },
                text = { Text(FORM_DISCLAIMER) },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putBoolean(DISCLAIMER_KEY, true).apply()
                        disclaimerAck = true
                    }) { Text("I understand") }
                },
            )
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
    val scroll = androidx.compose.foundation.rememberScrollState()
    val fadePx = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
    var selX by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Bring the selected item to the left edge so its label is never cut mid-word.
    LaunchedEffect(activeConfig.type, selX) { scroll.animateScrollTo((selX - fadePx).toInt().coerceAtLeast(0)) }
    Row(
        modifier = Modifier
            .hudBacking()
            .clip(RoundedCornerShape(12.dp))
            .drawWithContent { // trailing fade while more items are off-screen to the right
                drawContent()
                if (scroll.canScrollForward) drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color.Transparent, SahiColors.Slate), startX = size.width - fadePx, endX = size.width,
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - fadePx, 0f),
                    size = androidx.compose.ui.geometry.Size(fadePx, size.height),
                )
            }
            .selectableGroup()
            .horizontalScroll(scroll),
    ) {
        (listOf(SquatExerciseConfig, PushUpExerciseConfig) + Lifts.all).forEach { config ->
            val selected = config.type == activeConfig.type
            Text(
                text = config.type.displayLabel,
                style = SahiType.label.copy(color = if (selected) SahiColors.Ink else SahiColors.Chalk),
                modifier = Modifier
                    .onGloballyPositioned { if (selected) selX = it.positionInParent().x.toInt() }
                    .background(
                        color = if (selected) SahiColors.Haldi else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
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

/** Dark translucent backing (Slate at 85%) so HUD text stays readable on bright camera scenes. */
private fun Modifier.hudBacking(): Modifier = background(SahiColors.Slate.copy(alpha = 0.85f), RoundedCornerShape(12.dp))

@Composable
private fun HudChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, style = SahiType.label.copy(color = if (enabled) SahiColors.Chalk else SahiColors.Dust),
        modifier = Modifier.heightIn(min = 48.dp).hudBacking().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp).wrapContentHeight(Alignment.CenterVertically),
    )
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

private const val DISCLAIMER_KEY = "form_disclaimer_ack"
