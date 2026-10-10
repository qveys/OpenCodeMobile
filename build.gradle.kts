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
    alias(libs.plugins.paparazzi) apply false
    // OPE-14 / OPE-213 — bug-focused static analysis. Applied at the root and to
    // every subproject (see below): the root task is the portable scan and the
    // subproject tasks add type resolution. The config lives in
    // config/detekt/detekt.yml.
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

// OPE-14 / OPE-213 — detekt static analysis.
//
// Two kinds of scan run, and `detektAll` is the single entry point for both
// (scripts/lint.sh -> ./gradlew detektAll):
//
//   1. the root `detekt` task scans every `.kt` source without type resolution,
//      so Kotlin/Native (`iosMain`) sources that detekt cannot type-resolve are
//      still checked; and
//   2. one type-resolved task per subproject compilation (`detektAndroidDebug`,
//      `detektDebugUnitTest`, ...). detekt only creates those when the plugin is
//      applied to the subproject, and it fills their `classpath` from the
//      compilation. Without that classpath detekt silently skips the
//      type-dependent rules (OPE-213).
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

// OPE-213 — apply detekt to every subproject so the plugin also registers the
// type-resolved tasks (they carry the compilation classpath).
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.files("$rootDir/config/detekt/detekt.yml"))
        // Collect every report under the root build directory, one folder per
        // module, so CI can publish all of them from one place.
        reportsDir = rootProject.file(
            "build/reports/detekt/" + path.trimStart(':').replace(':', '/'),
        )
    }
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        // Tasks without a compile classpath cannot resolve types. The plain
        // per-project `detekt` task and the Kotlin/Native + metadata tasks fall
        // in this group: the first duplicates the root scan above and the others
        // are already covered by it. Skip them so a type-resolution run does not
        // analyse the same source twice.
        onlyIf { !classpath.isEmpty }
    }
}

// OPE-213 — single lint entry point: the portable root scan plus every
// type-resolved subproject task.
tasks.register("detektAll") {
    group = "verification"
    description = "Run detekt over every Kotlin source, with type resolution where possible."
    dependsOn(tasks.named("detekt"))
    subprojects.forEach { subproject ->
        dependsOn(subproject.tasks.withType<io.gitlab.arturbosch.detekt.Detekt>())
    }
}
