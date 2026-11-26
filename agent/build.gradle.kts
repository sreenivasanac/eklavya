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

// Android ships org.json; on the JVM (tests, the eval run) the same API comes from this artifact.
val orgJson = "org.json:json:20260814"

dependencies {
    api(project(":screen"))
    compileOnly(orgJson)
    testImplementation(orgJson)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Runs the decision test set against real models: ./gradlew :agent:eval  (needs API keys in the environment).
tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "Ask each configured decision model for the next step on recorded screens and report accuracy, time and cost."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.pragnyalabs.eklavya.agent.EvalKt"
}

// The recorded 24GO screens live with the screen module's tests; the agent's tests replay the same files.
sourceSets["test"].resources.srcDir("../screen/src/test/resources")
