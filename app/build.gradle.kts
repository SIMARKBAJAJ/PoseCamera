import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.posecamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.posecamera"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    val cameraX = "1.6.1"
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")

    testImplementation("junit:junit:4.13.2")
}

val poseModel = layout.projectDirectory.file("src/main/assets/pose_landmarker_lite.task")
val downloadPoseModel by tasks.registering {
    description = "Downloads the MediaPipe Pose Landmarker Lite model."
    group = "build setup"
    outputs.file(poseModel)
    doLast {
        val target = poseModel.asFile
        if (target.exists() && target.length() > 0L) return@doLast
        target.parentFile.mkdirs()
        val temporary = target.resolveSibling("${target.name}.download")
        URI.create(
            "https://storage.googleapis.com/mediapipe-models/pose_landmarker/" +
                "pose_landmarker_lite/float16/1/pose_landmarker_lite.task",
        ).toURL().openStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        check(temporary.length() > 0L) { "Downloaded pose model is empty" }
        check(temporary.renameTo(target)) { "Could not install pose model" }
    }
}

tasks.named("preBuild").configure { dependsOn(downloadPoseModel) }
