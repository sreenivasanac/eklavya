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
// app:    the Eklavya app.
include(":screen", ":a11y", ":app")
