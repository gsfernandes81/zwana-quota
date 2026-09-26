plugins {
    // The 8.x line on purpose: AGP 9 changes how Kotlin is wired in, and
    // nothing here needs it.
    id("com.android.application") version "8.13.0" apply false
    kotlin("android") version "2.4.20" apply false
}
