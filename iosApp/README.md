# iosApp

iOS host per Cahier des charges v1.0 §5.1: platform actuals (Keychain, notifications,
`SFSpeechRecognizer`) and the SwiftUI entry point that hosts the Compose Multiplatform UI.

## Why this isn't a Gradle module

Unlike `androidApp/`, an iOS app is an Xcode project (`.xcodeproj`/`.xcworkspace`), not a
Gradle subproject — Gradle cannot build or link it. `settings.gradle.kts` intentionally
does not `include(":iosApp")`.

Each `shared/*`, `features/*`, `design-system`, and `iosAppHost` Kotlin Multiplatform module
declares its own iOS framework export (`binaries.framework { isStatic = true }` on
`iosX64`/`iosArm64`/`iosSimulatorArm64`) instead of routing through one umbrella "shared"
framework, since §5.1 lists no such aggregator module. Xcode links the set of per-module
`.framework` bundles directly, the same way `androidApp` adds one Gradle module dependency
per module.

## The Kotlin composition root: `:iosAppHost`

The Swift shell is not allowed to see the whole dependency graph, and it cannot start a Kotlin
Koin graph or build a Compose UI. The Kotlin half of the iOS app shell therefore lives in
`iosAppHost/` (Gradle module `:iosAppHost`, static framework `iosAppHost`), the exact
counterpart of `androidApp`:

- `iosAppHost/src/iosMain/kotlin/.../IosConnectionCompositionRoot.kt` assembles the real
  `OpenCodeGateway` graph from `shared/networking` + `shared/security` behind Koin
  (`iosConnectionCompositionModule`), starts it (`startIosKoin`), and exposes
  `connectionSetupViewController()`. That builder hosts `ConnectionSetupScreen` with a
  `ConnectionSetupController` from Koin and an `IosQrCodeScanner` built from the Compose host
  controller via `LocalUIViewController`.
- `features/connection`'s `IosQrCodeScanner` (AVFoundation) is passed to the screen here; the
  camera port stays out of Koin, exactly as `AndroidQrCodeScanner` does on Android.
- `ContentView.swift` embeds the returned `UIViewController` with
  `UIViewControllerRepresentable`; `iOSApp.swift` calls `startIosKoin()` once at launch.

This module exists because the composition root cannot be a `features/*` module: §5.2 forbids a
feature from reaching `shared/networking`/`shared/security`. The deviation from the §5.1
module list is recorded in `docs/adr/0006-ios-composition-root-module.md`.

## The Xcode project (`project.yml`, generated `iosApp.xcodeproj`)

The Swift entry-point source (`iosApp/iOSApp.swift`, `ContentView.swift`), `Info.plist`, the
Kotlin `:iosAppHost` composition root, and the Xcode host project are all in the repo. The
project is described by [`project.yml`](project.yml) and generated with
[XcodeGen](https://github.com/yonaskolb/XcodeGen):

```bash
cd iosApp && xcodegen generate        # writes iosApp.xcodeproj
```

`project.yml` (not a hand-edited `.xcodeproj`) is the source of truth, so the project stays
reviewable and regenerable. The generated `iosApp.xcodeproj` is disposable and ignored by git
except for `project.pbxproj`, which may be committed for convenience.

The app target is iOS 16+, uses `iosApp/Info.plist` (with `NSCameraUsageDescription`), and
links the per-module **static** Kotlin/Native frameworks directly — no umbrella framework
(ADR 0001 §3): `iosAppHost`, `featuresConnection`, `designSystem`, `sharedDomain`,
`sharedApplication`, `sharedSecurity`. Modules without a framework binary
(`shared/networking`, `shared/tls-test-support`) are compiled into the frameworks that depend
on them.

A `Build Kotlin frameworks` run-script build phase runs
[`scripts/ios/build-frameworks.sh`](../scripts/ios/build-frameworks.sh) before Swift
compilation. It derives the Kotlin/Native target from Xcode's `PLATFORM_NAME`/`ARCHS` and the
Gradle variant from `CONFIGURATION`, builds the module frameworks, and stages them in
`iosApp/build/frameworks`, where `FRAMEWORK_SEARCH_PATHS` points.

`.github/workflows/ios-app.yml` builds the app for an iOS simulator on the company `mac`
self-hosted runner, launches it, and runs `iosAppUITests/ConnectionScreenUITests.swift`, which
checks the OPE-153 runtime acceptance (connection screen opens; scan/cancel returns; valid vs.
non-import QR payloads). The QR payload is injected through the debug-only
`-OPEQRPayload <value>` launch argument because the simulator has no camera.

## Platform actuals

Keychain, notification, and `SFSpeechRecognizer` `actual` implementations live in the
`iosMain` source set of the module that owns the corresponding `expect` declaration
(`shared/security` for Keychain/notifications, `features/composer` for dictation) — not in this
directory. `iosApp` only hosts the SwiftUI shell; the Kotlin composition root in `:iosAppHost`
starts Koin and presents the Compose UI.
