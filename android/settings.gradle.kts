// The Android app: the home-screen widget, and the Connect IQ companion that
// pushes the same reading to a Garmin watch. See README.md beside this file.
//
// Its portal client and derivation are the separate build in core/, pulled
// in here as a composite: core has no Android in it, so it can be built and
// tested where Google's Maven cannot be reached, and this build cannot.

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

rootProject.name = "zwana-quota-android"

includeBuild("core")
include(":app")
