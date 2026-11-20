// Vendored Astronomy Engine (Kotlin/JVM) by Don Cross — MIT License (see LICENSE).
// Source: https://github.com/cosinekitty/astronomy (source/kotlin). Kept unmodified.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    compilerOptions { suppressWarnings.set(true) }
}
