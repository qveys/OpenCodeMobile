# ADR 0008: iOS composition root as a Kotlin Multiplatform module (`:iosAppHost`)

- **Status**: accepted
- **Date**: 2026-09-29
- **Driven by**: OPE-153 (host the connection screen on iOS and pass `IosQrCodeScanner`)
- **References**:
  - Cahier des charges d'architecture v1.0 §5.1/§5.2 (attached to OPE-2)
  - ADR 0001 (monorepo module scaffold: per-module iOS frameworks, `iosApp` not a Gradle module)
  - ADR 0003 (pure Compose Multiplatform on iOS; `iosApp` stays a minimal host)
  - ADR 0004 (Konsist dependency rules; Koin modules only in `androidApp` and `features/*`)
  - OPE-148 (`androidApp/ConnectionCompositionRoot.kt`, `IosQrCodeScanner`)

## Context

§5.1 fixes the Gradle module list and puts the composition root in `androidApp` (Kotlin) and
`iosApp` (Swift/Xcode). ADR 0001 §4 records that `iosApp` is deliberately **not** a Gradle
module, so it cannot compile Kotlin. OPE-148 shipped the real `IosQrCodeScanner`
(`features/connection/src/iosMain`) and the Android composition-root wiring, but the iOS side
has no call site that starts Koin, hosts the Compose UI, and constructs the scanner, so the
iOS feature is unreachable.

The Kotlin half of the iOS host cannot live in the obvious places:

- `iosApp` is Swift/Xcode; it can neither start a Koin graph nor host a Compose UI directly.
- A `features/*` module must not reach `shared/networking`/`shared/security` (§5.2 rule 8), yet
  assembling the real `OpenCodeGateway` requires both.
- `shared/*` layers forbid Compose UI and Koin module declarations (ADR 0004 rules 10 and 12).

## Decision

Add `iosAppHost/` as a top-level Kotlin Multiplatform module (`:iosAppHost`), iOS targets only,
exporting the static framework `iosAppHost`. It is the iOS composition root, the counterpart of
`androidApp`:

- it declares `iosConnectionCompositionModule` (Keychain stores, TOFU/TLS identity, HTTP client,
  `OpenCodeV2Adapter`, `ConnectionSetupController`) and starts Koin (`startIosKoin`);
- it exposes `connectionSetupViewController()`, a `ComposeUIViewController` that injects the
  controller, builds `IosQrCodeScanner` from the Compose host controller
  (`LocalUIViewController`), and hosts `ConnectionSetupScreen`;
- the Swift shell calls `IOSApp.init { startIosKoin() }` and embeds the returned controller with
  `UIViewControllerRepresentable`.

This extends the "composition root" category of ADR 0004 rule 12 from `androidApp` to the iOS
app shell; the rule's intent — Koin assembly lives only in a host module — is preserved, not
loosened.

## Consequences

- One more static framework per iOS target for the Xcode project to link. ADR 0001 §3 already
  established per-module framework linking, so this adds one line to the (still ungenerated)
  Xcode "Link Binary With Libraries" phase.
- `settings.gradle.kts`, `docs/ARCHITECTURE.md`, and `iosApp/README.md` list the module; the
  `Build` workflow's iOS job already links every module at the root and now asserts
  `iosAppHost.framework` exists.
- The Konsist scope in `architecture-tests/` covers `commonMain` of the listed modules and is
  unchanged; `iosAppHost` is `iosMain`-only. If the scope is widened later, the module must be
  treated like `androidApp` (allowed to see infrastructure and declare Koin).
- Device/simulator validation of the acceptance criteria (scan opens capture, cancel returns,
  import link opens review) needs a generated Xcode project and an Apple toolchain, which is the
  ADR 0001 §4 follow-up and out of scope for this ADR.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Put the composition root in `features/connection` | Violates §5.2 rule 8 (feature → networking/security). |
| Put it in `shared/data` | Forbids Compose UI hosting and Koin modules (ADR 0004 rules 10, 12); also can't depend on `shared/application`/the feature controller. |
| Make `iosApp/` itself a Gradle module | ADR 0001 §4: an Xcode project cannot be a Gradle module; Gradle cannot build/link it. |
| Do the graph assembly in Swift | Swift cannot construct Koin definitions or Compose `UIViewController`s without a Kotlin entry point. |
