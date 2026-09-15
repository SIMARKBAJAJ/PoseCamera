package com.example.posecamera.pose

data class PosePoint(
    val x: Float,
    val y: Float,
    val visibility: Float = 1f,
    val presence: Float = 1f,
)

data class PoseFrame(
    val landmarks: List<PosePoint>,
    val imageWidth: Int,
    val imageHeight: Int,
    val fps: Float,
    val mirrorHorizontally: Boolean,
    val analysis: ExerciseAnalysis,
    val timestampMillis: Long,
)
