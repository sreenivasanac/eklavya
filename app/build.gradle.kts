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
        versionName = "0.1.0"
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
}
