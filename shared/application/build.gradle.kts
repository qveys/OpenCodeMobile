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
            baseName = "sharedApplication"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // V1-07/V1-09 app wiring end to end: the controllers and their
            // realtime bridge are driven by the real EventProcessor pipeline +
            // OpenCodeV2Adapter against MockOpenCodeServer.
            implementation(project(":shared:realtime"))
            implementation(project(":shared:networking"))
            implementation(project(":shared:test-support"))
            implementation(libs.ktor.client.core)
        }
    }
}

android {
    namespace = "org.opencodemobile.shared.application"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
