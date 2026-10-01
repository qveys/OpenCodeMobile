plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "designSystem"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // Exposed with `api`: every features/* module and the Android host render copy
            // through the CMP resource API (`stringResource(Res.string.…)`), so they need the
            // resources runtime on their compile classpath. `generateResClass = auto` also
            // requires an explicit dependency on this library in the module that owns the
            // composeResources directory.
            api(compose.components.resources)
            implementation(libs.koin.compose)
            implementation(libs.kotlinx.coroutines.core)

        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    // The generated `Res` class is consumed by the Android host and, later, by every
    // features/* module, so it must be public and live in a stable, owned package
    // (cahier des charges §9.6 / §13.2).
    publicResClass = true
    packageOfResClass = "org.opencodemobile.design.system.resources"
}

android {
    namespace = "org.opencodemobile.design.system"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}
