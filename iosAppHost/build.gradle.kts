plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // iOS only: this module is the Kotlin half of the iOS app shell. It is not
    // built or linked by androidApp; the Xcode project links its static
    // framework like every other per-module framework (ADR 0001 §3).
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "iosAppHost"
            isStatic = true
        }
    }

    sourceSets {
        iosMain.dependencies {
            // Composition root: like androidApp, it is the only place allowed to
            // see every module and assemble the real OpenCodeGateway graph
            // (docs/adr/0008-ios-composition-root-module.md).
            implementation(project(":features:connection"))
            implementation(project(":shared:domain"))
            implementation(project(":shared:application"))
            implementation(project(":shared:networking"))
            implementation(project(":shared:security"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
