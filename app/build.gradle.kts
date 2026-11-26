plugins {
    id("com.android.application")
}

android {
    namespace = "com.pragnyalabs.eklavya"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pragnyalabs.eklavya"
        // 33 = Android 13, where a receiver must say whether it is exported (DebugCommands).
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        // The wake-word library ships native code for four processor types; phones need only this one.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures {
        buildConfig = true
    }
    // The pose model is read straight from the APK, which only works if it is stored uncompressed.
    androidResources {
        noCompress += "task"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":a11y"))
    implementation(project(":agent"))
    implementation(project(":motion"))
    // The voice session is a WebSocket; Android has no built-in client for one.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // On-device wake word (keyword spotting).
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1")
    // Rep counting: camera frames (CameraX) through an on-device pose model (MediaPipe).
    val camerax = "1.7.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    // The camera follows a lifecycle, which SetWatcher provides; CameraX alone pulls in a version too old to implement.
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    // 1.2.2 on purpose: 1.2.3 has reports of the pose model failing to start on some phones.
    implementation("com.google.mediapipe:tasks-vision:1.2.2")
}
