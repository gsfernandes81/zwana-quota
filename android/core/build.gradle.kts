import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
}

// The coordinates the app build substitutes this build in for.
group = "io.github.gsfernandes81.zwanaquota"
version = "1"

// Bytecode for Java 17, compiled by whatever JDK is present. No toolchain
// block: provisioning one would mean a download from a host that may be
// blocked, and 17 is what both Android and the JDK 21 here can run.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The runtime's JSON tree only -- no serialization compiler plugin, so the
    // documents are read field by field, the way the Python reads them.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The golden vectors live at the repo root, shared with the Python that
    // writes them (vectors/make_vectors.py).
    val vectors = rootDir.resolve("../../vectors/quota.json")
    inputs.file(vectors)
    systemProperty("vectors", vectors.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
