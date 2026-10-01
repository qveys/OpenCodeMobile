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
            baseName = "sharedDomain"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The EventSource/EventTransport ports expose Flow/StateFlow/SharedFlow and take a
            // CoroutineScope, so the domain owns the coroutine types that define the contract.
            // This is the domain's only dependency besides the Kotlin stdlib (ADR 0001 §5, as
            // amended by OPE-245). No infrastructure dependency.
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // The offline mutation-gate tests drive suspend writers with runBlocking.
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "org.opencodemobile.shared.domain"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
