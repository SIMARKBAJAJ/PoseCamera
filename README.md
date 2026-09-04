# PoseCamera

On-device Android pose preview using CameraX, Jetpack Compose, and MediaPipe Pose Landmarker. The app requests camera permission, supports front/rear lenses, overlays all 33 BlazePose landmarks and standard connections, and reports completed-inference FPS.

## Toolchain and dependencies

- Android Gradle Plugin 8.13.2, Gradle 8.13, JDK 17
- Kotlin and Compose compiler plugin 2.2.21
- compileSdk/targetSdk 36; minSdk 24
- Compose BOM 2026.08.00, Activity Compose 1.13.0
- CameraX 1.6.1 (`core`, `camera2`, `lifecycle`, `view`)
- MediaPipe Tasks Vision 1.0.0

`app/build.gradle.kts` contains the complete dependency configuration. Before each build, `downloadPoseModel` ensures the official `pose_landmarker_lite.task` exists under `app/src/main/assets`. The packaged app does not use the network.

## Run

1. Install Android Studio with JDK 17 and Android SDK 36.
2. Open this directory in Android Studio and allow Gradle sync to finish.
3. Run on an API 24+ physical Android device and grant camera permission.

From a configured terminal:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The first build downloads the 5–6 MB lite model. MediaPipe first attempts its GPU delegate and recreates the landmarker on CPU if GPU initialization fails.

## Pipeline

CameraX groups preview and analysis under the same `PreviewView` viewport and analyzes only the newest 640×480 RGBA frame on one executor. The viewport crop is copied into reusable storage, the `ImageProxy` is closed immediately, and MediaPipe applies display rotation through `ImageProcessingOptions` before `LIVE_STREAM` inference. Only one inference is in flight at a time; front-camera mirroring is applied once when the viewport-normalized landmarks are rendered.
