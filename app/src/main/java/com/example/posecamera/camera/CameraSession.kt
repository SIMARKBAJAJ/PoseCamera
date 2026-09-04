package com.example.posecamera.camera

import android.content.Context
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
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(resolution)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        analysis.setAnalyzer(executor) { image -> poseEngine.process(image, isFront) }
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
