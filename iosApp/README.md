# iosApp

iOS host per Cahier des charges v1.0 §5.1: platform actuals (Keychain, notifications,
`SFSpeechRecognizer`) and the SwiftUI entry point that hosts the Compose Multiplatform UI.

## Why this isn't a Gradle module

Unlike `androidApp/`, an iOS app is an Xcode project (`.xcodeproj`/`.xcworkspace`), not a
Gradle subproject — Gradle cannot build or link it. `settings.gradle.kts` intentionally does
not `include(":iosApp")`.

Each `shared/*`, `features/*`, and `design-system` Kotlin Multiplatform module declares its own
iOS framework export (`binaries.framework { isStatic = true }` on `iosX64`/`iosArm64`/
`iosSimulatorArm64`) instead of routing through one umbrella "shared" framework, since §5.1 lists
no such aggregator module. Xcode links the set of per-module `.framework` bundles directly,
the same way `androidApp` adds one Gradle module dependency per module.

## What's here vs. what's deferred

This scaffold ships the Swift entry-point source (`iosApp/iOSApp.swift`, `ContentView.swift`)
and `Info.plist` so the intended structure and platform-actual boundaries are visible in the
repo. It does **not** ship a generated `.xcodeproj` — that file format needs Xcode (or
`xcodegen`) to produce correctly, and this scaffolding pass ran in a Linux sandbox with no
Xcode toolchain and no network access to Apple/CocoaPods infrastructure to validate one.
Generating and committing the real Xcode project, and wiring the per-module `.framework`
outputs into its "Link Binary With Libraries" build phase, is tracked as follow-up work (see
`docs/adr/0001-monorepo-module-scaffold.md`) rather than attempted here.

## Platform actuals

Keychain, notification, and `SFSpeechRecognizer` `actual` implementations live in the
`iosMain` source set of the module that owns the corresponding `expect` declaration
(`shared/security` for Keychain/notifications, `features/composer` for dictation) — not in this
directory. `iosApp` only hosts the SwiftUI shell that starts Koin and presents the Compose UI.
