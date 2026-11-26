import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Replays recorded poses through the counter and prints what the coach would say:
// ./gradlew :motion:replay -Pposes=/folder/of/pose/files
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Count reps and list cues for every pose file in a folder."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.pragnyalabs.eklavya.motion.ReplayKt"
    args((findProperty("poses") as String?) ?: "src/test/resources/poses")
}
