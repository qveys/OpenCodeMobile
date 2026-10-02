plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "sharedRealtime"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:networking"))
            implementation(libs.kotlinx.coroutines.core)
            // Payload validation (malformed events) only; the domain stays JSON-free.
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // End-to-end pipeline test: the mock server harness plus the raw
            // Ktor client it exposes to build a real transport.
            implementation(project(":shared:test-support"))
            implementation(libs.ktor.client.core)
        }
    }
}

android {
    namespace = "org.opencodemobile.shared.realtime"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
