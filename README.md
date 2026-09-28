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

### Core Capabilities

- **Real-Time Session Tracking**: Stream active agent thoughts, command executions, and progress in real time via Server-Sent Events (SSE) with resilient exponential backoff and polling fallback.
- **Interactive Permission Approval**: Grant or deny granular tool execution requests (terminal commands, file writes, web requests) directly from push notifications or inside the app.
- **Agent Chat & Voice Dictation**: Send instructions, clarify context, and answer agent questions. Supports on-device speech-to-text dictation to keep prompt audio private.
- **File & Diff Review**: Inspect modified files and syntax-highlighted diffs on mobile before approving commits or test executions.
- **Privacy & Security First**: Zero intermediary cloud servers, zero prompt or code telemetry. Direct point-to-point connections with credentials stored exclusively in Android Keystore / iOS Keychain.

---

## Tech Stack & Architecture

OpenCode Mobile follows Clean Architecture principles in a feature-modular Kotlin Multiplatform monorepo.

| Component | Technology | Rationale |
|---|---|---|
| **Core & Shared Logic** | Kotlin Multiplatform (KMP 2.1+) | Maximum shared code across Android (API 31+) and iOS (iOS 16+). |
| **User Interface** | Compose Multiplatform (Material 3) | Unified, declarative UI with platform-native adaptations. |
| **Networking & API** | Ktor HTTP Client + OpenAPI Generator | Type-safe generated client isolated behind `OpenCodeGateway` adapter. |
| **Realtime Engine** | Custom `EventProcessor` | Unified SSE pipeline with automatic snapshot reconciliation and fallback. |
| **Local Storage** | SQLDelight + SQLCipher | Offline-first, encrypted local cache for session history. |
| **Dependency Injection** | Koin Multiplatform | Lightweight, idiomatic multiplatform service locator and DI. |
| **Platform Integration** | `expect` / `actual` | Hardware-backed Keychain/Keystore, biometric auth, and local notifications. |

---

## Project Structure

```text
├── composeApp/                 # Compose Multiplatform UI application
│   ├── androidMain/            # Android-specific entry point & actuals
│   ├── commonMain/             # Shared UI components, screens, navigation
│   └── iosMain/                # iOS-specific entry point & actuals
├── shared/                     # Business logic and data modules
│   ├── core/                   # Utilities, error handling, dispatchers
│   ├── domain/                 # Domain entities, repositories, use cases
│   ├── data/                   # SQLDelight database, network clients, adapters
│   └── security/               # Keystore / Keychain secure storage
├── design-system/              # Imported token/logo sources and references
├── docs/                       # Architecture decisions, threat models, specs
└── scripts/                    # Maintenance, CI/CD, and verification scripts
```

---

## Development & Git Workflow

- **Branch Protection**: Direct pushes to `main` are disabled. All changes must be submitted via pull request.
- **Continuous Integration**: Every PR must pass compilation, static analysis (linting), unit tests, and architecture dependency validation.
- **Fork PR Security**: In compliance with threat model policy T10, GitHub Actions workflows for first-time contributors require maintainer approval before running.
- **Conventions**: Conventional Commits format (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`).

For full details, see [`docs/git-workflow.md`](docs/git-workflow.md), [`docs/pr-conventions.md`](docs/pr-conventions.md), and [`docs/BRANCH-PROTECTION.md`](docs/BRANCH-PROTECTION.md).

## Design System

The visual foundation — colour tokens, typography, spacing, component patterns and brand rules — is specified in [`docs/DESIGN-SYSTEM.md`](docs/DESIGN-SYSTEM.md). Imported sources and their integrity records are in [`design-system/`](design-system/README.md).

---

## License

This project is licensed under the [MIT License](LICENSE).
