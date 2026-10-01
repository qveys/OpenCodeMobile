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
    // OPE-14 — bug-focused static analysis. Applied to the root project so the
    // `lint` gate is a single task/report (see the `detekt` block below and
    // scripts/lint.sh). The config lives in config/detekt/detekt.yml.
    alias(libs.plugins.detekt)
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

// OPE-14 — detekt scans every Kotlin source from one root task, so CI has a
// single `lint` check (scripts/lint.sh -> ./gradlew detekt).
//
// Only `.kt` is scanned. Gradle `.kts` build scripts are excluded because
// detekt cannot type-resolve the Gradle Kotlin DSL and flags the delegated
// source-set properties (`val androidUnitTest by getting`) as unused.
// Generated code is excluded because it is machine-owned; its correctness is
// covered by the generated-types serialization tests instead.
detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        fileTree(rootDir) {
            include("**/*.kt")
            exclude(
                "**/build/**",
                "**/.gradle/**",
                "**/.kotlin/**",
                "**/.paperclip/**",
                "**/generated/**",
            )
        },
    )
}