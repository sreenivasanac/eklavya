plugins {
    id("com.android.library")
}

android {
    namespace = "com.pragnyalabs.eklavya.a11y"
    compileSdk = 36

    defaultConfig {
        // 30 = Android 11, the first release with the "IME enter" accessibility action.
        minSdk = 30
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":screen"))
}
