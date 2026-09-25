# OpenCode Mobile

[![CI](https://github.com/qveys/OpenCodeMobile/actions/workflows/ci.yml/badge.svg)](https://github.com/qveys/OpenCodeMobile/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-teal.svg)](https://www.jetbrains.com/lp/compose-multiplatform/)

> [!IMPORTANT]
> **Non-Affiliation Disclaimer**: OpenCode Mobile is an independent, community-driven open-source project and is **NOT affiliated with, sponsored by, or endorsed by Anomaly or opencode.ai**. OpenCode Server is an open-source project; OpenCode Mobile is a native companion client for users running their own server instances.

---

## Overview

**OpenCode Mobile** is a native companion application for Android and iOS that acts as a remote control for your self-hosted **OpenCode Server v2** instance. Whether your server runs on your local machine, a workstation across your local area network (LAN), or a private Tailscale tailnet, OpenCode Mobile lets you interact with autonomous coding sessions from your phone without opening your laptop.

The app is a **client only**. It has no product backend, runs no cloud proxy, and collects no prompt or code telemetry. Your OpenCode Server remains the single source of truth; the on-device cache is disposable and can be rebuilt from the server at any time.

### Core capabilities

- **Real-time session tracking** — stream agent thoughts, command executions, and progress in real time over Server-Sent Events (SSE), with exponential backoff and a polling fallback.
- **Interactive permission approval** — review and grant or deny tool-call permissions. Approval always requires an explicit foreground confirmation plus platform biometrics.
- **Agent chat and dictation** — send prompts and answer agent questions, with strictly on-device speech-to-text dictation (no audio leaves the device).
- **File and diff review** — inspect modified files and diffs on mobile.
- **Security first** — trust-on-first-use (TOFU) server identity pinning, credentials in Android Keystore / iOS Keychain, encrypted cache, zero third-party telemetry.

---

## Tech stack

OpenCode Mobile follows Clean Architecture principles in a feature-modular Kotlin Multiplatform monorepo.

| Component | Technology | Notes |
|---|---|---|
| **Shared logic** | Kotlin Multiplatform 2.1.0 | One codebase targets Android (minSdk 31) and iOS (16+). |
| **User interface** | Compose Multiplatform 1.7.1 (Material 3) | Shared, declarative UI on both platforms (ADR 0003). |
| **HTTP / API** | Ktor 3.0.1 + OpenAPI-generated client | Generated types stay inside `shared/networking` (ADR 0002). |
| **Realtime** | Custom `EventProcessor` | SSE with polling fallback and snapshot reconciliation. |
| **Local cache** | SQLDelight 2.0.2 | Offline, read-only, encrypted at rest, disposable. |
| **Dependency injection** | Koin 4.0.0 | Composition root in `androidApp`. |
| **Serialization / time** | kotlinx.serialization 1.7.3, kotlinx-datetime 0.6.1 | |
| **Architecture tests** | Konsist 0.8.0 | Enforces module dependency rules in CI (ADR 0004). |

Pinned versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

---

## Repository layout

```text
.
├── androidApp/            # Android host app and Koin composition root
├── iosApp/                # iOS host (Swift entry point; not a Gradle module)
├── shared/
│   ├── domain/            # Entities, value objects, ports (Kotlin stdlib only)
│   ├── application/       # Use cases / interactors
│   ├── data/              # Repository implementations and adapters
│   ├── networking/        # Ktor client + generated OpenAPI client
│   ├── realtime/          # EventProcessor (SSE + polling fallback)
│   ├── persistence/       # SQLDelight cache
│   ├── security/          # Keychain/Keystore, TLS pinning, biometrics
│   └── test-support/      # Shared fakes, fixtures, MockOpenCodeServer
├── features/              # One module per feature (connection, projects,
│                          # sessions, transcript, composer, files,
│                          # permissions, settings)
├── design-system/         # Theme, typography, Compose components
├── architecture-tests/    # Konsist module-boundary tests
├── docs/                  # Architecture, API, contribution, security docs
├── scripts/               # Maintenance, CI/CD, and verification scripts
└── gradle/                # Version catalog and wrapper
```

The module list and the forbidden dependency edges are fixed by the architecture specification and enforced by [`architecture-tests/`](architecture-tests) (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)).

---

## Getting started

### Prerequisites

- **JDK 21** (subprojects target JVM 17–21).
- **Android SDK** with API 35 (`compileSdk 35`, `minSdk 31`) and the Android Studio toolchain, for the Android app.
- **Xcode 16+** on macOS, for the iOS app.
- A reachable **OpenCode Server v2** instance (`opencode serve`). No product backend is required.

### Build the shared modules

```bash
git clone https://github.com/qveys/OpenCodeMobile.git
cd OpenCodeMobile

# Compile the shared Kotlin Multiplatform graph
./gradlew build
```

### Run the Android app

```bash
./gradlew :androidApp:installDebug
```

Or open the project in Android Studio and run the `androidApp` configuration.

### Run on iOS

`iosApp/` is a native Xcode project host, **not** a Gradle module. It currently ships the Swift entry point and `Info.plist` only; generating and committing the `.xcodeproj` and wiring the per-module frameworks is tracked as follow-up work. See [`iosApp/README.md`](iosApp/README.md) and [ADR 0001](docs/adr/0001-monorepo-module-scaffold.md).

### Generate the API client

The typed OpenCode Server v2 client is generated from the pinned OpenAPI specification:

```bash
./gradlew :shared:networking:generateOpenApiClient
```

Generated code is never hand-edited. See [docs/API.md](docs/API.md).

### Tests

```bash
# Unit tests across all modules
./gradlew test

# Architecture / module-boundary rules (Konsist)
./gradlew :architecture-tests:test
```

---

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — system context, module map, dependency rules, data flow, security design.
- [docs/API.md](docs/API.md) — OpenCode Server v2 API surface used by the app, authentication, and client generation.
- [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) — contribution guide: workflow, commits, review tiers, testing, architecture rules.
- [docs/git-workflow.md](docs/git-workflow.md) and [docs/pr-conventions.md](docs/pr-conventions.md) — detailed branch/PR conventions.
- [docs/BRANCH-PROTECTION.md](docs/BRANCH-PROTECTION.md) — protected-branch and required-check rules.
- [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md) and [docs/CI-CD-SECURITY.md](docs/CI-CD-SECURITY.md) — security model and CI hardening.
- [ROADMAP.md](ROADMAP.md) — milestones, lots L0–L6, and delivery gates.
- [docs/adr/](docs/adr/) — architecture decision records.

---

## Contributing

All changes ship through a pull request against `main` — direct commits are disabled, and commits must be signed/verified. Use Conventional Commits with an emoji prefix, keep architectural boundaries intact, and never commit secrets.

See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) for the full guide.

---

## License

This project is licensed under the [MIT License](LICENSE).