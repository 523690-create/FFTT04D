import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // Android target (AGP 9+ KMP library plugin). Consumed by the :app module.
    androidLibrary {
        namespace = "com.example.FFTT04M.shared"
        compileSdk = 36
        minSdk = 23

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm() // Desktop target

    // iOS targets (iPhone + iPad). Configure-only on Windows; the framework links on macOS.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // commonMain holds the shared, platform-agnostic DSP (FFTUtils, BiquadFilter).
    // No extra dependencies needed — pure kotlin.math.
}
