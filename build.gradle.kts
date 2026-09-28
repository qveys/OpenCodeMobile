// Root build file: declares plugins for all subprojects to apply, without applying them here.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sqldelight) apply false
}

// Pin the Kotlin JVM bytecode target for every module so it always matches the
// `compileOptions { sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17 }`
// set in the Android modules. Without an explicit target the Kotlin Gradle plugin
// follows the JDK running Gradle (21 on the CI runners), and AGP fails the build
// with "Inconsistent JVM-target compatibility detected for tasks
// 'compileDebugJavaWithJavac' (17) and 'compileDebugKotlinAndroid' (21)".
// Pinning keeps local and CI output consistent regardless of the host JDK.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}