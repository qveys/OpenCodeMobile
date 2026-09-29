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

## What's here vs. what's deferred

This scaffold ships the Swift entry-point source (`iosApp/iOSApp.swift`, `ContentView.swift`),
`Info.plist`, and the Kotlin `:iosAppHost` composition root. It does **not** ship a generated
`.xcodeproj` — that file format needs Xcode (or `xcodegen`) to produce correctly, and the
scaffolding pass ran in a Linux sandbox with no Xcode toolchain and no network access to
Apple/CocoaPods infrastructure to validate one. Generating and committing the real Xcode
project, and wiring the per-module `.framework` outputs (including `iosAppHost.framework`) into
its "Link Binary With Libraries" build phase, is tracked as follow-up work (see
`docs/adr/0001-monorepo-module-scaffold.md`) rather than attempted here.

## Platform actuals

Keychain, notification, and `SFSpeechRecognizer` `actual` implementations live in the
`iosMain` source set of the module that owns the corresponding `expect` declaration
(`shared/security` for Keychain/notifications, `features/composer` for dictation) — not in this
directory. `iosApp` only hosts the SwiftUI shell; the Kotlin composition root in `:iosAppHost`
starts Koin and presents the Compose UI.
