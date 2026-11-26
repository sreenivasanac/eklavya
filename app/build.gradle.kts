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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":a11y"))
    implementation(project(":agent"))
    // The voice session is a WebSocket; Android has no built-in client for one.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // On-device wake word (keyword spotting).
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1")
}
