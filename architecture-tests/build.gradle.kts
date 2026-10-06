import org.gradle.api.tasks.PathSensitivity

// JVM-only module that hosts the Konsist module-boundary tests (ADR 0004 / §5.2).
// It analyzes Kotlin sources statically, so it is intentionally *not* a KMP
// module: no Android/iOS targets, no dependency on any shipped module.
plugins {
    alias(libs.plugins.kotlinJvm)
}

// The root build pins the Kotlin JVM target to 17 for every subproject, so the
// Java compile tasks must target 17 as well or Gradle rejects the mismatch
// ("Inconsistent JVM-target compatibility").
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotest.core)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Konsist reports paths relative to the "root project path" it detects, and
    // the test scope paths are repository-relative. Running the tests from the
    // Gradle root project keeps local and CI behaviour identical.
    workingDir = rootProject.projectDir
    // The test task reads other modules' sources as data. Declare them as task
    // inputs, otherwise Gradle sees this module unchanged and reports the task
    // UP-TO-DATE, letting a forbidden dependency through unchecked.
    inputs
        .files(
            rootProject.fileTree(rootProject.projectDir) {
                include(
                    "shared/*/src/commonMain/kotlin/**/*.kt",
                    "features/*/src/commonMain/kotlin/**/*.kt",
                    "features/*/src/androidMain/kotlin/**/*.kt",
                    "features/*/src/iosMain/kotlin/**/*.kt",
                    "features/*/src/androidMain/AndroidManifest.xml",
                    "design-system/src/commonMain/kotlin/**/*.kt",
                    "androidApp/src/main/kotlin/**/*.kt",
                    "iosApp/iosApp/Info.plist",
                )
            },
        ).withPropertyName("analyzedKotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
