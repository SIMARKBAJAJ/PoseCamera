package com.example.posecamera.pose

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class PoseEngine(
    context: Context,
    private val inferenceExecutor: ExecutorService,
    private val onFrame: (PoseFrame) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fpsTracker = FpsTracker()
    private var landmarker: PoseLandmarker? = null
    private var initializationAttempted = false
    private var lastTimestamp = 0L
    private var inferenceInFlight = false
    private var frameBuffer: ByteBuffer? = null
    private var frameBitmap: Bitmap? = null
    private var pendingFrame: FrameMetadata? = null
    private val closed = AtomicBoolean(false)
    private var closeFinalized = false

    fun initialize() {
        if (closed.get()) return
        submitInternal {
            if (!closed.get()) initializeOnExecutor()
        }
    }

    private fun initializeOnExecutor() {
        if (closed.get()) return
        if (initializationAttempted) return
        initializationAttempted = true
        landmarker = runCatching { create(Delegate.GPU) }
            .recoverCatching { create(Delegate.CPU) }
            .onFailure { reportError(it.message ?: "Pose model could not start") }
            .getOrNull()
    }

    private fun create(delegate: Delegate): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_FILE)
            .setDelegate(delegate)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setResultListener { result, _ ->
                submitInternal {
                    val metadata = pendingFrame
                    pendingFrame = null
                    inferenceInFlight = false
                    if (!closed.get() && metadata != null) {
                        val landmarks = result.landmarks().firstOrNull().orEmpty().map {
                            PosePoint(
                                x = it.x(),
                                y = it.y(),
                                visibility = it.visibility().orElse(0f),
                                presence = it.presence().orElse(0f),
                            )
                        }
                        val completedAtMillis = SystemClock.uptimeMillis()
                        val fps = fpsTracker.record(completedAtMillis)
                        val squatForm = analyzeSquatForm(
                            landmarks,
                            metadata.orientedWidth,
                            metadata.orientedHeight,
                        )
                        postToMain {
                            onFrame(
                                PoseFrame(
                                    landmarks = landmarks,
                                    imageWidth = metadata.orientedWidth,
                                    imageHeight = metadata.orientedHeight,
                                    fps = fps,
                                    mirrorHorizontally = metadata.mirrorHorizontally,
                                    squatForm = squatForm,
                                    timestampMillis = completedAtMillis,
                                ),
                            )
                        }
                    }
                    finalizeCloseIfReady()
                }
            }
            .setErrorListener {
                submitInternal {
                    pendingFrame = null
                    inferenceInFlight = false
                    if (!closed.get()) reportError(it.message ?: "Pose inference failed")
                    finalizeCloseIfReady()
                }
            }
            .build()
        return PoseLandmarker.createFromOptions(appContext, options)
    }

    fun process(image: ImageProxy, mirrorHorizontally: Boolean) {
        if (closed.get()) {
            image.close()
            return
        }
        try {
            inferenceExecutor.execute {
                if (closed.get()) {
                    image.close()
                } else {
                    processOnExecutor(image, mirrorHorizontally)
                }
            }
        } catch (_: RejectedExecutionException) {
            image.close()
        }
    }

    private fun processOnExecutor(image: ImageProxy, mirrorHorizontally: Boolean) {
        try {
            if (!initializationAttempted) initializeOnExecutor()
            val activeLandmarker = landmarker
            if (closed.get() || activeLandmarker == null || inferenceInFlight) return
            val rotation = image.imageInfo.rotationDegrees
            val crop = image.cropRect
            val width = crop.width()
            val height = crop.height()
            val bitmap = copyRgbaCrop(image, crop.left, crop.top, width, height)
            val timestamp = max(SystemClock.uptimeMillis(), lastTimestamp + 1L)
            lastTimestamp = timestamp
            val swapsDimensions = rotation % 180 != 0
            pendingFrame = FrameMetadata(
                orientedWidth = if (swapsDimensions) height else width,
                orientedHeight = if (swapsDimensions) width else height,
                mirrorHorizontally = mirrorHorizontally,
            )
            inferenceInFlight = true
            val processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()
            activeLandmarker.detectAsync(
                BitmapImageBuilder(bitmap).build(),
                processingOptions,
                timestamp,
            )
        } catch (error: RuntimeException) {
            pendingFrame = null
            inferenceInFlight = false
            if (!closed.get()) reportError(error.message ?: "Pose frame processing failed")
            finalizeCloseIfReady()
        } finally {
            image.close()
        }
    }

    private fun copyRgbaCrop(
        image: ImageProxy,
        cropLeft: Int,
        cropTop: Int,
        width: Int,
        height: Int,
    ): Bitmap {
        require(width > 0 && height > 0) { "Camera supplied an empty crop" }
        val plane = image.planes.firstOrNull()
            ?: throw IllegalArgumentException("Camera supplied no RGBA plane")
        require(plane.pixelStride >= BYTES_PER_PIXEL) { "Invalid RGBA pixel stride" }
        val requiredCapacity = width * height * BYTES_PER_PIXEL
        val packed = frameBuffer?.takeIf { it.capacity() >= requiredCapacity }
            ?: ByteBuffer.allocateDirect(requiredCapacity).also { frameBuffer = it }
        packed.clear()

        val source = plane.buffer.duplicate()
        for (y in 0 until height) {
            var sourceOffset = (cropTop + y) * plane.rowStride + cropLeft * plane.pixelStride
            if (plane.pixelStride == BYTES_PER_PIXEL) {
                source.position(sourceOffset)
                source.limit(sourceOffset + width * BYTES_PER_PIXEL)
                packed.put(source)
                source.clear()
            } else {
                for (x in 0 until width) {
                    source.position(sourceOffset)
                    source.limit(sourceOffset + BYTES_PER_PIXEL)
                    packed.put(source)
                    source.clear()
                    sourceOffset += plane.pixelStride
                }
            }
        }
        packed.flip()
        val bitmap = frameBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                frameBitmap = it
            }
        bitmap.copyPixelsFromBuffer(packed)
        return bitmap
    }

    private fun submitInternal(action: () -> Unit) {
        try {
            inferenceExecutor.execute(action)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun reportError(message: String) = postToMain { onError(message) }

    private fun postToMain(action: () -> Unit) {
        if (closed.get()) return
        mainHandler.post {
            if (!closed.get()) action()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        submitInternal { finalizeCloseIfReady() }
    }

    private fun finalizeCloseIfReady() {
        if (!closed.get() || inferenceInFlight || closeFinalized) return
        closeFinalized = true
        try {
            landmarker?.close()
        } finally {
            landmarker = null
            pendingFrame = null
            frameBitmap = null
            frameBuffer = null
            inferenceExecutor.shutdown()
        }
    }

    private data class FrameMetadata(
        val orientedWidth: Int,
        val orientedHeight: Int,
        val mirrorHorizontally: Boolean,
    )

    companion object {
        private const val MODEL_FILE = "pose_landmarker_lite.task"
        private const val BYTES_PER_PIXEL = 4
        val connections: List<Pair<Int, Int>> = PoseLandmarker.POSE_LANDMARKS.map {
            it.start() to it.end()
        }
    }
}
