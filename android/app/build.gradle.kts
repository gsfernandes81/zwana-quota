import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

// Release signing comes from the environment, which only CI sets (see
// .github/workflows/android-apk.yml): a keystore from the repository's
// secrets, so every build upgrades over the last one in place. Built
// anywhere else, the release is signed with the local debug key.
val keystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")

android {
    namespace = "io.github.gsfernandes81.zwanaquota"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.gsfernandes81.zwanaquota"
        // java.time without desugaring, and adaptive icons.
        minSdk = 26
        targetSdk = 36
        // CI numbers the build by commit count, so a newer build always
        // installs over an older one.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "dev"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // No R8: nothing here is big enough to be worth shrinking, and a
            // crash nobody can read a stack trace of is not worth the bytes.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Lint runs as its own CI step, which prints this text report into the
        // job log; it does not stand between a build and its artifact.
        checkReleaseBuilds = false
        textReport = true
        textOutput = file("build/reports/lint-results.txt")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The portal client, the derivation and the face: ../core, substituted
    // in by the composite build.
    implementation("io.github.gsfernandes81.zwanaquota:core")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.work:work-runtime:2.10.0")
    // Garmin's Connect IQ Mobile SDK, from Maven Central. Its own manifest
    // brings the <queries> entry for Garmin Connect; nothing else is needed.
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")
}
