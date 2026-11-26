pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (the wake word) publishes its Android library only through JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("""com\.github\.k2-fsa.*""") }
        }
    }
}
rootProject.name = "eklavya"
// screen: pure Kotlin model + text form of a screen (JVM-testable).
// a11y:   Android library that reads and drives screens through an AccessibilityService.
// motion: counts reps and checks form from body poses; pure Kotlin.
// agent:  decides the next step towards a goal (Jev or another model) and carries it out; pure Kotlin.
// app:    the Eklavya app.
include(":screen", ":a11y", ":motion", ":agent", ":app")
