package com.example.posecamera.camera

import android.content.Context
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.example.posecamera.pose.PoseEngine
import com.example.posecamera.power.FrameThrottle
import com.example.posecamera.power.PowerPolicy
import com.example.posecamera.power.Resolution
import java.util.concurrent.ExecutorService

class CameraSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val executor: ExecutorService,
    private val poseEngine: PoseEngine,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var activeAnalysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var policy = PowerPolicy.NORMAL
    private var boundResolution: Resolution? = null
    private val throttle = FrameThrottle()

    fun attach(view: PreviewView) {
        previewView = view
        view.doOnLayout {
            if (previewView === view) bind()
        }
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { cameraProvider ->
                        provider = cameraProvider
                        bind()
                    }
                    .onFailure { onError(it.message ?: "Camera unavailable") }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /** Main thread. The fps cap applies immediately; a new resolution rebinds the camera. */
    fun applyPolicy(newPolicy: PowerPolicy) {
        policy = newPolicy
        throttle.setCap(newPolicy.analysisFpsCap)
        if (newPolicy.analysisResolution != boundResolution) bind()
    }

    fun toggleLens() {
        val requestedLens = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val cameraProvider = provider ?: return
        val requestedSelector = selector(requestedLens)
        if (!runCatching { cameraProvider.hasCamera(requestedSelector) }.getOrDefault(false)) {
            onError("Requested camera is unavailable")
            return
        }
        lensFacing = requestedLens
        bind()
    }

    private fun bind() {
        val cameraProvider = provider ?: return
        val view = previewView ?: return
        val viewPort = view.viewPort ?: return
        var cameraSelector = selector(lensFacing)
        if (!runCatching { cameraProvider.hasCamera(cameraSelector) }.getOrDefault(false)) {
            val fallbackLens = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            val fallbackSelector = selector(fallbackLens)
            if (!runCatching { cameraProvider.hasCamera(fallbackSelector) }.getOrDefault(false)) {
                onError("No compatible camera is available")
                return
            }
            lensFacing = fallbackLens
            cameraSelector = fallbackSelector
        }
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            ).build()
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(resolution)
            .build()
            .also { it.surfaceProvider = view.surfaceProvider }
        val target = policy.analysisResolution
        val analysisResolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(target.width, target.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            ).build()
        boundResolution = target
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(analysisResolution)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        analysis.setAnalyzer(executor) { image ->
            if (throttle.allow(SystemClock.uptimeMillis())) poseEngine.process(image, isFront) else image.close()
        }
        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(analysis)
            .setViewPort(viewPort)
            .build()

        runCatching {
            activeAnalysis?.clearAnalyzer()
            activeAnalysis = null
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                useCaseGroup,
            )
            activeAnalysis = analysis
        }.onFailure {
            analysis.clearAnalyzer()
            onError(it.message ?: "Could not start camera")
        }
    }

    fun close() {
        activeAnalysis?.clearAnalyzer()
        activeAnalysis = null
        provider?.unbindAll()
        previewView = null
    }

    private fun selector(lens: Int) = CameraSelector.Builder()
        .requireLensFacing(lens)
        .build()
}
