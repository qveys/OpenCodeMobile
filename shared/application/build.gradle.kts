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
            // V1-06 ingress end to end: the permission surface is driven by the
            // real EventProcessor pipeline + gateway against MockOpenCodeServer.
            implementation(project(":shared:realtime"))
            implementation(project(":shared:networking"))
            implementation(project(":shared:test-support"))
            implementation(libs.ktor.client.core)
        }

        // OPE-180 / D8: the cache write path is proven through the production
        // surface — CacheWritePipeline + a real CacheStack over an in-memory
        // SQLite driver (the Android driver is not usable off-device). The
        // cache/data modules are test-only dependencies; commonMain stays
        // Domain-only (D-architecture tests scan commonMain).
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":shared:data"))
                implementation(project(":shared:persistence"))
                implementation(libs.sqldelight.sqlite.driver)
            }
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
