plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "org.opencodemobile.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.opencodemobile.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Composition root: the only place allowed to see every module (§5.2 constrains
    // features/UI and domain, not the app shell that wires them together via Koin).
    implementation(project(":design-system"))
    implementation(project(":features:connection"))
    implementation(project(":features:projects"))
    implementation(project(":features:sessions"))
    implementation(project(":features:transcript"))
    implementation(project(":features:composer"))
    implementation(project(":features:files"))
    implementation(project(":features:permissions"))
    implementation(project(":features:questions"))
    implementation(project(":features:catalog"))
    implementation(project(":features:settings"))

    implementation(project(":shared:domain"))
    implementation(project(":shared:application"))
    implementation(project(":shared:data"))
    implementation(project(":shared:networking"))
    implementation(project(":shared:realtime"))
    implementation(project(":shared:persistence"))
    implementation(project(":shared:security"))

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    // The host renders copy through the CMP resource API (design-system's public `Res`).
    implementation(compose.components.resources)
    implementation(libs.androidx.activity.compose)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.kotlinx.coroutines.core)

    // Unit test for the composition-root resolver (OPE-170): a JVM-hosted test
    // that drives the real Koin graph. `kotlin("test")` selects the JVM/JUnit
    // variant that provides `kotlin.test.Test` and the assertions.
    testImplementation(kotlin("test"))
    testImplementation(libs.koin.core)
    // S10a composition-root test drives the real adapter against
    // MockOpenCodeServer, so the app module's test classpath needs the shared
    // test-support module plus the Ktor / coroutines-test types it uses.
    testImplementation(project(":shared:test-support"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.core)
}
