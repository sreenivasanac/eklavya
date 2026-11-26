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
    }
}
rootProject.name = "eklavya"
// screen: pure Kotlin model + text form of a screen (JVM-testable).
// a11y:   Android library that reads and drives screens through an AccessibilityService.
// agent:  decides the next step towards a goal (Jev or another model) and carries it out; pure Kotlin.
// app:    the Eklavya app.
include(":screen", ":a11y", ":agent", ":app")
