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
                // A keystore made without a password is opened with an empty
                // one: Java reads that, and refuses a missing (null) one. So
                // an unset password is empty, the key's password is the
                // store's, and the alias is the README's.
                val store = System.getenv("ANDROID_KEYSTORE_PASSWORD").orEmpty()
                storeFile = file(keystore)
                storePassword = store
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "zwana"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: store
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

    testOptions {
        // Robolectric renders the screens from the real resources (ScreensTest).
        unitTests.isIncludeAndroidResources = true
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
    // Material 3 for the app screen: cards, the switch, the chip, the meter,
    // and dynamic colour from the wallpaper. The widget does not use it.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Garmin's Connect IQ Mobile SDK, from Maven Central. Its own manifest
    // brings the <queries> entry for Garmin Connect; nothing else is needed.
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")

    // Renders the screen to PNGs on the JVM so it can be looked at without a
    // phone: Robolectric's native graphics draw real pixels.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
}
