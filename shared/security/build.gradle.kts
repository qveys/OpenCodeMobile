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
            baseName = "sharedSecurity"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // T1 real-handshake fixtures + in-process TLS test server (OPE-94).
            implementation(project(":shared:tls-test-support"))
        }

        androidMain.dependencies {
            implementation(libs.okhttp)
        }

        // The Android handshake tests build an OkHttp client directly (OPE-94);
        // `androidMain`'s implementation dependency is not reliably exported to
        // the test compilations, so depend on OkHttp explicitly.
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.okhttp)
            }
        }

        // Instrumentation runtime for the on-device T1 handshake test (OPE-94).
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.okhttp)
                implementation("androidx.test.ext:junit:1.2.1")
                implementation("androidx.test:runner:1.6.2")
            }
        }
    }
}

android {
    namespace = "org.opencodemobile.shared.security"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
