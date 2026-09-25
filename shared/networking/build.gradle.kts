plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "org.opencode.mobile.networking"
    compileSdk = 35
    defaultConfig {
        minSdk = 31
    }
}

// Wired code generation task: generates Kotlin Multiplatform client from pinned OpenAPI spec
tasks.register<Exec>("generateOpenApiClient") {
    group = "openapi"
    description = "Generates Kotlin Multiplatform API client from the vendored OpenCode Server v2 OpenAPI spec"
    workingDir = rootDir
    commandLine = listOf("bash", "scripts/generate-openapi-client.sh")
    inputs.file("shared/networking/openapi/opencode-server-v2.json")
    outputs.dir("shared/networking/src/commonMain/kotlin/org/opencode/mobile/networking/client/generated")
}
