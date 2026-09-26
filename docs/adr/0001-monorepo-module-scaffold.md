# ADR 0001: Monorepo module scaffold

- Status: accepted
- Date: 2026-09-24
- Driven by: OPE-30, implementing Cahier des charges d'architecture v1.0 §5.1/§5.2 (attached to OPE-2)

## Context

§5.1 fixes the module list (`androidApp/`, `iosApp/`, `shared/{domain,application,data,
networking,realtime,persistence,security,test-support}`, `features/{connection,projects,
sessions,transcript,composer,files,permissions,settings}`, `design-system/`) and §5.2 fixes the
forbidden dependency edges. Neither section specifies Gradle/Kotlin tooling versions, package
naming, or how a Kotlin/Native build reaches an iOS app target — those calls had to be made to
produce a buildable scaffold, and per the document's own rule ("toute déviation passe par un
ADR") anything not literally dictated by §5.1/§5.2 is recorded here.

## Decisions

1. **Package namespace**: `org.opencodemobile`, mirrored per module
   (`org.opencodemobile.shared.domain`, `org.opencodemobile.features.composer`, ...). Not
   specified anywhere in the cahier des charges; chosen for an open-source, non-affiliated
   project with no reserved domain yet.

2. **Toolchain versions** (`gradle/libs.versions.toml`): Kotlin 2.1.0, AGP 8.7.2, Compose
   Multiplatform 1.7.1, Ktor 3.0.1, SQLDelight 2.0.2, Koin 4.0.0, Gradle 8.10.2. `docs/TECH-STACK.md`
   names the libraries but explicitly deferred exact versions to "once the project scaffold
   exists" — this is that scaffold, so pinning them here is in scope, not a deviation.

3. **One iOS framework export per Kotlin Multiplatform module**, instead of a single umbrella
   `shared` framework. §5.1 lists no aggregator module that would produce one consolidated
   framework for Xcode to link, so each `shared/*`, `features/*`, and `design-system` module
   declares its own static framework (`binaries.framework { isStatic = true }` on
   `iosX64`/`iosArm64`/`iosSimulatorArm64`), and `iosApp` is expected to link each one directly —
   the same granularity `androidApp` already gets via one Gradle module dependency per module.

4. **`iosApp/` ships as Swift source + `Info.plist` only, no generated `.xcodeproj`.** A real
   Xcode project needs Xcode (or `xcodegen`) to produce and validate correctly; this scaffolding
   pass ran in a Linux sandbox with no Xcode toolchain and no network path to Apple/CocoaPods
   infrastructure. Generating the actual `.xcodeproj` and wiring the per-module framework outputs
   into its build phases is follow-up work, not attempted here. `iosApp` is therefore not a
   Gradle subproject (`settings.gradle.kts` does not include it) — an Xcode project cannot be a
   Gradle module regardless of this decision.

5. **`shared/domain` carries zero Gradle dependencies** (not even `kotlinx-coroutines-core`),
   matching §5.1's literal "Kotlin stdlib uniquement" rather than only the acceptance criteria's
   shorter forbidden-list (which doesn't mention coroutines). Consequence: domain ports that need
   to be reactive/streaming can't return `Flow` yet; that's a real constraint for whoever
   implements `shared/domain`'s ports next, worth knowing rather than silently loosening the rule.

## Consequences / known gaps

- Build verification in this sandbox was static only: there is no JDK and no network egress to
  Maven Central or `services.gradle.org` (proxy allowlist), so `./gradlew build` could not be run
  to confirm the module graph actually compiles. The Gradle wrapper jar in this repo was fetched
  from the official `gradle/gradle` v8.10.2 tag on GitHub (the one host the sandbox proxy
  allowed) and its byte size matches GitHub's reported blob size for that path, but a real build
  (or at minimum `./gradlew :shared:domain:compileKotlinMetadata` in CI) is the first real
  confirmation this scaffold is correct.
- `androidApp` and `iosApp` are excluded from the "builds cleanly" acceptance bar for the same
  reason CI's own bootstrap-phase note (`docs/CI-CD.md`) uses: an Android SDK/Xcode toolchain
  isn't available everywhere the scaffold itself needs to be reviewable.
- The CI architecture/dependency-rule tests that would enforce §5.2 automatically are explicitly
  out of scope for this issue (tracked separately in the roadmap's first backlog batch, item 4)
  — this scaffold only encodes the rules via which Gradle module depends on which.
