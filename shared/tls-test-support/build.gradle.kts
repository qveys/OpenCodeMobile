plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// OPE-94 — test-only TLS fixtures for the T1 real-handshake validation.
//
// Deliberately dependency-free: `shared:security` tests depend on this module,
// and `shared:security` is `api`-exposed by `shared:networking`, which
// `shared:test-support` already depends on. Reusing `shared:test-support` here
// would close a security -> test-support -> networking -> security cycle, so
// the fixtures live in their own leaf module.
kotlin {
    androidTarget()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    )
}

android {
    namespace = "org.opencodemobile.shared.testsupport.tls"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}