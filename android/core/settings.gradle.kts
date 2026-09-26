// The portal client and the derivation, with no Android in them.
//
// A build of its own rather than a subproject of ../, on purpose: Gradle
// configures every project in a build before it runs any task, so a :core
// beside :app could not even be tested without resolving the Android Gradle
// plugin -- and the machines this is written on cannot reach Google's Maven.
// Standalone, `./gradlew -p core test` needs nothing but Maven Central.
// ../settings.gradle.kts pulls it into the app build with includeBuild().

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "core"
