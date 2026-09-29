rootProject.name = "opencode-mobile"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Android host app (§5.1)
include(":androidApp")
// iosApp/ is a native Xcode project, not a Gradle module — see iosApp/README.md.
// Its Kotlin composition root (Koin graph + Compose host) cannot live in the
// Swift/Xcode project, so it is built as :iosAppHost and linked by the Xcode
// project like every other per-module framework. See iosApp/README.md and
// docs/adr/0006-ios-composition-root-module.md.
include(":iosAppHost")

// shared/* — Kotlin Multiplatform, dependency direction enforced by §5.2
include(":shared:domain")
include(":shared:application")
include(":shared:data")
include(":shared:networking")
include(":shared:realtime")
include(":shared:persistence")
include(":shared:security")
include(":shared:test-support")
include(":shared:tls-test-support")

// features/* — one module per feature (§5.1)
include(":features:connection")
include(":features:projects")
include(":features:sessions")
include(":features:transcript")
include(":features:composer")
include(":features:files")
include(":features:permissions")
include(":features:questions")
include(":features:catalog")
include(":features:settings")

// design-system/ — theme, typography, CMP components
include(":design-system")
