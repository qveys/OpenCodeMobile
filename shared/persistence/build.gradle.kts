plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "sharedPersistence"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            // T3 / OP2: SQLCipher-encrypted Android driver.
            implementation(libs.sqlcipher.android)
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        // JVM unit tests exercise the SQLDelight schema/queries over a plain
        // in-memory SQLite driver (the Android driver is not usable off-device).
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        // On-device T3 proof: open the SQLCipher DB, write a marker, then read the
        // raw bytes off disk and assert the marker is not present in cleartext.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.sqlcipher.android)
                implementation("androidx.test.ext:junit:1.2.1")
                implementation("androidx.test:runner:1.6.2")
            }
        }
    }
}

android {
    namespace = "org.opencodemobile.shared.persistence"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("Cache") {
            packageName.set("org.opencodemobile.shared.persistence.db")
        }
    }
}
