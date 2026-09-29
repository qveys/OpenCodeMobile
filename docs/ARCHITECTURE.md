# Architecture — OpenCode Mobile

This document is the system architecture of OpenCode Mobile: component
structure, data flow, API boundaries, and deployment model. It supersedes the
placeholder that previously deferred this scope to OPE-5.

Status: **complete for the V1 architecture**. The detailed security sections
under **Detailed security design** record mandatory decisions in full and are
binding for implementation. Where a component still needs its own deep dive,
the owning issue is named explicitly instead of silently omitting it.

Scope sources: the *Cahier des charges d'architecture v1.0* (attached to
OPE-2), `docs/TECH-STACK.md` (OPE-4), `docs/THREAT-MODEL.md` (OPE-7),
`docs/CI-CD-SECURITY.md`, `ROADMAP.md`, the ADRs in `docs/adr/`, and the
KMP/CMP scaffold landed by PR #14 on branch
`OPE-14-add-linter-and-configure-lint-rules`.

---

## 1. System context

OpenCode Mobile is a thin, native client. It talks directly to the user's own
OpenCode Server v2 over LAN or Tailscale. There is no product backend, no
relay, and no prompt or code telemetry.

```text
┌──────────────────────────┐      HTTPS / SSE       ┌───────────────────────────┐
│  OpenCode Mobile (app)   │  ───────────────────▶  │  OpenCode Server v2       │
│  Android  ·  iOS         │  ◀───────────────────  │  (self-hosted by user)    │
│  Compose UI + shared KMP │      /event stream     │  sole source of truth     │
└──────────────────────────┘                        └───────────────────────────┘
      │  disposable, encrypted cache (read-only offline)
      ▼
  SQLDelight
```

Seven properties define every choice below:

1. **The server is authoritative.** Sessions, messages, permissions,
   questions, and file state come from the server. The local cache only
   serves offline reads and is rebuilt from the next server snapshot.
2. **Point-to-point connection.** The app connects to the server address the
   user configured; credentials and traffic never traverse a third party.
3. **The app is a remote control, not an IDE.** It starts, watches, and
   steers agent work; it does not edit code, run a model, or reimplement
   OpenCode.
4. **Mobile networks are hostile.** Connection loss, backgrounding, network
   changes, and flaky VPN paths are the normal case, not edge cases.
5. **Consequential actions require foreground, human confirmation.** Approving
   a tool call authorizes code execution on the user's machine.
6. **Platform-native where the platform owns it.** Secure storage,
   biometrics, notifications, and speech recognition are per-platform behind
   `expect`/`actual`; everything else is shared Kotlin.
7. **One maintainer.** Boundaries are enforced by machine-checked rules and
   CI, not by review capacity.

---

## 2. Component structure

### 2.1 Layers

Clean Architecture, dependency direction pointing inward only:

| Ring | Modules | Responsibility |
|---|---|---|
| Domain | `shared/domain` | Entities, value objects, ports. Kotlin stdlib only. |
| Application | `shared/application` | Use cases orchestrating domain ports and coroutines. |
| Infrastructure | `shared/data`, `shared/networking`, `shared/realtime`, `shared/persistence`, `shared/security` | Adapters for server protocol, event stream, cache, and platform security. |
| Presentation | `features/*`, `design-system`, `androidApp`, `iosApp` | Compose UI, ViewModels, navigation, platform host shells. |

### 2.2 Module map

| Module | Kind | Responsibility |
|---|---|---|
| `androidApp` | Android app | Host shell, Koin composition root, platform `actual` wiring. |
| `iosApp` | Swift/Xcode host | Swift entry point; not a Gradle module. See `iosApp/README.md`. |
| `shared/domain` | KMP library | Entities, value objects, and ports. Kotlin stdlib only. |
| `shared/application` | KMP library | Use cases that orchestrate domain ports and coroutines. |
| `shared/data` | KMP library | Repository implementations, coordinating networking, realtime, persistence, and security. |
| `shared/networking` | KMP library | Ktor client, `OpenCodeV2Adapter`, generated OpenAPI client, log redaction. |
| `shared/realtime` | KMP library | Event pipeline: SSE, polling fallback, backoff, snapshot reconciliation. |
| `shared/persistence` | KMP library | SQLDelight cache schema and drivers. |
| `shared/security` | KMP library | Secure storage, TOFU/TLS identity verification, biometrics. |
| `shared/test-support` | KMP library | Fakes, fixtures, and the deterministic `MockOpenCodeServer`. |
| `features/{connection,projects,sessions,transcript,composer,files,permissions,settings}` | KMP libraries | One module per user-facing capability. |
| `design-system` | KMP library | Design tokens, typography, and reusable Compose components. |
| `architecture-tests` | JVM test module | Konsist assertions that enforce the dependency rules below. |

The Gradle module list is authoritative in `settings.gradle.kts`; the scaffold
is recorded in ADR 0001. `iosApp` is intentionally not a Gradle subproject: it
is an Xcode project linking one static framework per Kotlin module (ADR 0001
§3, §4).

### 2.3 Ownership and seams

Each layer owns one kind of truth, and the seams between them are the ports
that cross-layer work goes through:

| Owner | Truth it owns | Seam it exposes |
|---|---|---|
| `shared/domain` | Domain model and policy-free ports | `OpenCodeGateway`, `ServerIdentityStore`, `ServerIdentityVerifier`, `EventSource`, `SessionCache` ports |
| `shared/networking` | Protocol shape: HTTP, SSE framing, generated DTOs, auth header, log redaction | Implements `OpenCodeGateway` (`OpenCodeV2Adapter`) |
| `shared/realtime` | Ordering, retry, reconciliation | Implements the event stream on top of the gateway |
| `shared/persistence` | Local cache schema only (disposable) | SQLDelight queries behind a domain port |
| `shared/security` | Credential storage, identity pins, biometric gate | Implements the domain security ports |
| `features/*` | Screen state and user intent | ViewModels exposing application-layer flows |
| `androidApp` / `iosApp` | Composition root only | Koin module assembly, platform `actual`s |

Rules that hold across all of them:

- **Generated OpenAPI types never leave `shared/networking`** (Rule R3,
  ADR-0002). `shared/networking` is the only module permitted to import
  `org.opencode.mobile.networking.client.generated.*`.
- **Features never touch infrastructure directly.** A feature depends on
  `shared/domain`, `shared/application`, `design-system`, Compose, and Koin —
  never Ktor, SQLDelight, the generated client, or another feature's
  internals.
- **`shared/domain` stays pure.** Not even `kotlinx-coroutines-core`
  (ADR 0001 §5); streaming ports that need `Flow` are resolved at the
  application layer.

### 2.4 Enforced dependency rules

The architecture specification fixes the edges below.
`architecture-tests/ModuleBoundaryTest.kt` (PR #14, ADR 0004) encodes them and
fails CI on any violation:

1. `shared/domain` depends only on the Kotlin stdlib.
2. `shared/application` may depend only on `shared/domain` and
   `kotlinx.coroutines`.
3. `shared/data` may depend on `shared/domain`, `shared/networking`,
   `shared/realtime`, `shared/persistence`, and `shared/security`.
4. `shared/networking` may depend only on `shared/domain` and Ktor. It
   exclusively owns the generated OpenAPI client.
5. `shared/realtime` may depend only on `shared/domain` and
   `shared/networking`.
6. `shared/persistence` may depend only on `shared/domain` and SQLDelight.
7. `shared/security` may depend only on `shared/domain`.
8. Each `features/*` module may depend only on `shared/domain`,
   `shared/application`, `design-system`, Compose, and Koin. Features must not
   reach into another feature's internals.
9. `design-system` may depend only on Compose and the Kotlin stdlib.
10. Features, `design-system`, and `androidApp` must not import Ktor,
    SQLDelight, or platform secure-storage APIs directly.
11. The generated client is never hand-edited.
12. Koin module declarations are permitted only in `androidApp` and
    `features/*` (never `shared/*` or `design-system`).

Additional CI gates in the same class:

- `scripts/verify-r3-generated-types.sh` — no generated-type import outside
  `shared/networking`.
- `scripts/check-no-secret-logging.sh` plus
  `.github/workflows/security-logging.yml` — no raw credential or prompt-body
  logging (T4).

---

## 3. Data flow

### 3.1 Connection, identity, and handshake

```text
profile (manual | deep link | QR)
   │
   ├─▶ import review screen ──persist──▶ secure storage (Keystore/Keychain)
   │
   ▼
OpenCodeGateway.connect(profile, credential)
   │
   ├─ 1. ServerIdentityGate.authorize(profile)     ← TOFU pin check, runs first
   │        first contact → ConfirmationRequired    (show fingerprint, ask user)
   │        pinned match  → Authorized             (permit released)
   │        mismatch      → Blocked                (fail closed, no credential)
   │
   ├─ 2. credential permit set (only after step 1)
   ├─ 3. platform TLS engine enforces the pin during handshake (defense in depth)
   ├─ 4. GET /global/health → version → CompatibilityProfile gate
   └─ 5. ConnectionHandshake(profileId, health, identity)
```

Failure modes are typed at the domain boundary (`DomainError`: unreachable,
incompatible server, untrusted identity, rejected credential) so the
connection feature can render a specific screen instead of a generic error.
The T1 design is mandatory and already implemented in `shared/security` and
`OpenCodeV2Adapter.connect`.

### 3.2 Realtime event pipeline

```text
GET /event (SSE, text/event-stream)
   │
   ▼
EventProcessor (one per connection; owned by shared/realtime)
   │  normalize → order → dedupe by server-issued ids
   ├─▶ in-memory model (source for Compose state)
   ├─▶ cache writer (shared/persistence, encrypted)
   └─▶ on stream loss:
          degrade to polling GET /session/status
          exponential backoff with a floor on the interval
          on reconnect: fetch snapshot → reconcile →
                        drop replayed/duplicate events → resume live
```

Three invariants:

- **Mutations are never auto-replayed.** A dropped connection must never
  re-send an approve/deny, prompt, or abort. Replay would double-submit an
  action with real side effects on the user's machine (T2/T6).
- **Reconciliation is idempotent.** Server-issued, single-use request ids
  (`^per`, `^ses`) make duplicate or out-of-order delivery harmless.
- **Offline is read-only.** While the stream is down the UI shows a persistent
  stale-data indicator and every mutating action is disabled at the use-case
  layer, not merely hidden in the UI (T12).

### 3.3 Prompt, permission, and question flows

**Send a prompt.** `POST /session/{sessionID}/prompt_async` is
fire-and-forget and returns `204` as soon as the server accepts it. The client
never uses a blocking prompt endpoint: reasoning, tool calls, text chunks,
permission requests, and questions all arrive on `/event`. Abort is
`POST /session/{sessionID}/abort`.

**Answer a permission request.**

```text
server emits permission request ──▶ /event ──▶ EventProcessor
   ├─▶ in-app pending list
   └─▶ local notification (informational; deep link only, no inline "Approve")
            │                        ("Deny" is allowed: safe, reversible)
            ▼
   foreground confirmation screen
      full command/diff disclosure · explicit distinct tap ·
      biometric gate · bound to request id + content hash
            ▼
   POST /permission/{requestID}/reply  { once | always | reject, message }
```

**Answer an agent question.** `GET /question` lists pending questions;
`POST /question/{requestID}/reply` takes `{ answers: [[label, ...]] }` and
`POST /question/{requestID}/reject` takes an empty body. Both accept the
optional `directory` query parameter, and the adapter always passes the active
project root so answers cannot cross-contaminate another workspace on the same
server (ADR-0002 §3.3).

**Read files and diffs.** `GET /file`, `GET /file/content`, `GET /file/status`
for the tree and read-only viewer; `GET /session/{sessionID}/diff` for unified
diffs. Responses are bounded with graceful truncation and a "view full file"
fallback (T7); the 5 MB viewer target is an L5 exit criterion.

### 3.4 Read path and cache

```text
Compose screen → ViewModel → application use case
                                 ├─ live: EventProcessor state (SSE-derived)
                                 └─ offline: SessionCache (SQLDelight, encrypted)
```

The cache is scoped `ServerId → ProjectId → SessionId`, written only from
events, and never the source of truth: it can be wiped and rebuilt from the
next snapshot at any time. Cache encryption, backup exclusion, and key-loss
handling are mandatory and specified below.

### 3.5 UI and dependency injection

Compose screens and shared ViewModels observe application-layer flows with
unidirectional data flow (`StateFlow` in, events out). Koin modules are
declared in `androidApp` (composition root) and `features/*`; the `iosApp`
Swift shell starts Koin and hosts the Compose UI. Pure Compose Multiplatform
owns navigation and lifecycle on both platforms — `iosApp` stays a thin host
(ADR 0003).

---

## 4. API boundaries

The app's entire external contract is the OpenCode Server v2 HTTP + SSE API.
The app exposes no API of its own. The endpoint tables, pinning metadata, and
generation procedure live in `docs/API.md`; the verified mapping between the
specification's assumed endpoints and the real spec is ADR-0002.

### 4.1 The five boundaries

| # | Boundary | Direction | Guarantee |
|---|---|---|---|
| 1 | App ↔ OpenCode Server v2 | outbound only | Point-to-point HTTPS + SSE over LAN/Tailscale. No relay, no third party, no telemetry. |
| 2 | App code ↔ generated OpenAPI client | inward | Generated types are confined to `shared/networking`; only `OpenCodeV2Adapter` may consume them (R3). |
| 3 | `OpenCodeGateway` port ↔ infrastructure | inward | Domain and application code see only domain types and typed errors. |
| 4 | App process ↔ platform secure services | platform | Credentials, identity pins, and the cache key live in Keychain/Keystore; no plaintext fallback (B2). |
| 5 | Repository ↔ release pipeline | supply chain | Fork PRs run without secrets; signing and upload restricted to protected `main` (T10). |

### 4.2 Pinned specification

| Field | Value |
|---|---|
| Server | OpenCode Server v2 `1.18.32` |
| OpenAPI | `3.1.0` |
| Vendored spec | `shared/networking/openapi/opencode-server-v2.json` |
| SHA-256 | `46db986090aae41846cd6dbe16225a1d883f0bbcb4c48814008d3f6ce140aa5c` |

The spec is vendored so builds are reproducible and never depend on a running
server, and it is regenerated only through a reviewed PR (T11). The generated
client is derived from that file; the pinned toolchain versions are in
`gradle/libs.versions.toml`.

### 4.3 Surfaces and transport rules

- **Root surface is canonical.** `OpenCodeV2Adapter` standardizes on
  `/global/health`, `/event`, `/session*`, `/permission`, `/question`,
  `/project`, `/config`, `/provider`, `/agent`, `/file`, `/find`. The
  experimental `/api/*` surface is reference material only (ADR-0002 §3.1).
- **SSE is the only realtime transport.** `/event` (`text/event-stream`),
  one-directional, plain HTTP, no upgrade handshake; polling
  `/session/status` is the fallback. WebSocket is not used.
- **Async prompt only.** No blocking prompt endpoint is ever called from the
  client.
- **One credential, one place.** `Authorization: Bearer <token>` (or the
  Basic form when a server is configured that way) is attached by the
  adapter's interceptor, and only while the identity gate has authorized the
  connection. The token is read from Keychain/Keystore, never persisted
  elsewhere, never logged (T4), and never embedded in a deep link or QR (T8).
- **Directory scoping.** Requests that can span workspaces carry the active
  project root as the `directory` parameter.
- **Error model.** The adapter maps HTTP and transport failures to typed
  domain errors; raw status codes and generated error types do not escape the
  adapter.

### 4.4 Compatibility policy

The server is external and versioned independently, so compatibility is
checked, not assumed: the handshake reads the version from
`GET /global/health` and gates the connection behind a `CompatibilityProfile`.
An incompatible server produces an explicit screen with the server's version
and the supported range. Contract tests against a real instance plus the
deterministic `MockOpenCodeServer` are the drift detector; the compatibility
matrix is an L6 deliverable.

Status (OPE-135): implemented. `CompatibilityProfile.OpenCodeServerV2` accepts
server major `1` at `>= 1.18.0`; `OpenCodeV2Adapter` parses the reported
version, gates it, and raises `HandshakeException`
(`HealthUnavailable` / `ServerUnhealthy` / `Incomplete` / `Incompatible`) for a
handshake that cannot be completed.

### 4.5 Connection policy (HTTP/TLS)

This resolves the open policy question in the T1 section: whether plaintext
HTTP is restricted to private address ranges.

- `HttpConnectionPolicy` classifies a profile host as `Loopback`, `Lan`,
  `Tailscale`, or `Public` (RFC 1918 + link-local + ULA, CGNAT
  `100.64.0.0/10` and `*.ts.net`, mDNS `.local`).
- HTTPS is allowed for every scope.
- Plaintext HTTP is allowed only on `Loopback`, `Lan`, and `Tailscale`; those
  profiles still carry the persistent plaintext warning and the TOFU identity
  check (T1).
- Plaintext HTTP to a `Public` host is rejected with
  `ConnectionPolicyException.Rejected` before any request is built: TLS is
  mandatory for any external connection, and the credential is never sent.

Status (OPE-135): implemented (`OpenCodeV2Adapter.connect` step 1,
`HttpConnectionPolicy`).

---

## 5. Deployment model

### 5.1 What is deployed

OpenCode Mobile ships as two native store applications. There is no server
side to deploy, no hosted environment to operate, and no infrastructure cost.

| Artifact | Platform | Distribution |
|---|---|---|
| Android app (`.aab`) | Android API 31+ | Google Play: internal → closed → public track |
| iOS app (`.ipa`) | iOS 26+ | TestFlight → App Store |

Two one-off, manual prerequisites sit outside the app itself:

- **Verified link domain.** App Links / Universal Links need an owned domain
  serving the association files; profile import prefers verified links over a
  custom scheme (T8). Until that domain exists, import works through the
  in-app QR scanner path, which shares the same review screen.
- **Store accounts and signing material.** A Play Console account, an Apple
  Developer account, an Android upload key, and an App Store Connect API key.
  These live as CI secrets and are never committed (T10).

### 5.2 Environments

| Environment | Purpose | Trigger | Secrets |
|---|---|---|---|
| Local | Development on one machine | manual | none committed |
| CI (PR) | Lint, architecture tests, unit tests, dual-platform build | every pull request | none (fork PRs run with no secrets) |
| CI (main) | Same gates on merge; signing and upload jobs are restricted to `main` | push to `main` | none until signing stage |
| Contract | Behavioral check against a real OpenCode instance | pipeline step | test server credential, scoped |
| Internal track | TestFlight / Play internal smoke | release workflow | signing + store credentials, environment-gated |
| Store | Public release | manual approval gate | signing + store credentials |

There is no separate staging or production server tier — the OpenCode Server
belongs to the user, not to the project.

### 5.3 Release pipeline

```text
PR ──▶ lint ──▶ architecture tests ──▶ unit tests ──▶ Android build + iOS build
                                                        │
                                        required review + branch protection
                                                        ▼
                                        merge to main (signed commits)
                                                        ▼
                                   release workflow ──▶ signed artifacts
                                                        ▼
                                   Play internal ──▶ TestFlight ──▶ public
                                                        ▲
                                            manual approval gate (§11)
```

Controls that are architecture, not process:

- **No direct commits to `main`.** Every change lands through a pull request.
- **Signed commits only.** The repository enforces `required_signatures`;
  commits are produced through the signed-commit path so GitHub verifies them.
- **Fork PRs never see secrets.** First-time contributor workflows require
  maintainer approval (T10, `docs/CI-CD-SECURITY.md`).
- **Signing restricted to `main`/tags**, with least-privilege workflow
  permissions and environment-gated credentials.
- **Release artifacts are immutable and reproducible** from the pinned spec,
  pinned toolchain, and signed commit.

### 5.4 Runtime deployment shape

- **Local data:** encrypted SQLDelight cache, credential and identity pins in
  Keychain/Keystore, both excluded from OS/cloud backups.
- **No background service:** the app holds one connection while it is
  running; it does not run a daemon, a sync worker, or a push relay. Local
  notifications are generated by the app for events it is already receiving.
- **Failure posture:** when the server is unreachable the app is a read-only
  cache viewer with an explicit stale indicator; it never queues writes to
  send later.

---

## 6. Cross-cutting concerns

| Concern | Decision | Where enforced |
|---|---|---|
| Concurrency and state | Coroutines + `StateFlow`, unidirectional data flow | `shared/application`, `features/*` |
| Dependency injection | Koin, runtime resolution, composition root only | `androidApp`, `features/*` |
| Localization | French + English from V1 | resources in `androidApp` / `iosApp` / `design-system` |
| Logging | One sanctioned sanitizing entry point | `shared/networking/.../logging/`, CI gate |
| Error handling | Typed `DomainError` at the domain boundary | `shared/domain`, `shared/networking` |
| Accessibility | Best-effort V1, full pass in L6 | `design-system`, `features/*` |
| Deviation control | Anything not literally fixed by the specification needs an ADR | `docs/adr/` |

---

## 7. Component deep-dive status

This document fixes the architecture. The following components still need
their own implementation-level specification or implementation, each owned by
a separate issue:

| Component | Status | Owner |
|---|---|---|
| Module scaffold | Landed by PR #14 (branch `OPE-14-add-linter-and-configure-lint-rules`) | OPE-14 / OPE-30 |
| Generated OpenAPI client | Spec pinned; adapter boundary recorded in ADR-0002 | OPE-31 |
| `MockOpenCodeServer` | Planned; contract-test seam fixed here | OPE-32 |
| Local cache schema (real tables) | Placeholder `Cache.sq` only | OPE-30 follow-up / L2 |
| EventProcessor | Architecture fixed in §3.2; implementation is L2 | L2 |
| Identity verification (T1) | Implemented in `shared/security` + `OpenCodeV2Adapter.connect` | OPE-35 |
| Permission approval flow (T2) | Design fixed below; implementation is L3 | OPE-25 |
| Cache encryption (T3) | Design fixed below | OPE-26 |
| Log redaction (T4) | Implemented; CI-enforced | OPE-56 |
| Dictation (T5) | Design fixed below | OPE-28 |
| Profile import (T8) | Design fixed below | OPE-29 |
| Design system | Skeleton in `design-system/`; visual language in L0 | OPE-6 |
| iOS POC | Required to validate CMP rendering on iOS 26+ | L0 |

---

## Detailed security design

The sections below record mandatory security decisions. They are binding for
implementation, not suggestions.

## Server profile import (deep link / QR)

A "server profile" is the app's stored record of one self-hosted OpenCode
Server: host, port, TLS/fingerprint info, and (if present) a credential used
to authenticate REST/SSE calls (see `docs/THREAT-MODEL.md` §3.1, B1, B2).
Profiles can be created two ways:

1. **Manual entry** — the user types the server address directly in-app.
2. **Import** — a deep link or QR code encodes the same information so the
   user doesn't have to type it. QR is purely a transport for the deep
   link: scanning a QR code decodes to the same URI the app's deep-link
   handler consumes, so both paths share one implementation and one review
   screen.

### Link format

- Prefer platform **verified links** (Android App Links / iOS Universal
  Links backed by the project's own verified domain) over a bare custom URL
  scheme. A custom scheme (e.g. `opencodemobile://`) can be registered by
  any other installed app on Android, which could let a malicious app
  intercept or spoof "import" links; a verified HTTPS link cannot be
  hijacked this way. If a custom scheme is also shipped (e.g. as a QR
  fallback for platforms/flows where verified links don't apply), it goes
  through the exact same review flow below — it is not treated as a
  lower-friction path.
- The link carries the server `host`, `port`, an optional display `label`,
  and an optional TLS fingerprint for trust-on-first-use verification (see
  T1 in the threat model). It must not carry a long-lived bearer credential
  in the URL itself — deep links can end up in browser history, share
  sheets, clipboard managers, and OS-level link logs. If pairing needs to
  hand over a secret, the link carries a short-lived, single-use pairing
  code that the app exchanges for a credential over a direct TLS connection
  to the target host, immediately after the user confirms the import (see
  below) — the code is not usable on its own to reach the server, and it
  expires quickly whether or not it's used.
- Unrecognized or malformed import links (missing host, unparseable) are
  discarded silently — they never partially populate or clear an existing
  profile.

### Mandatory review before persisting (T8 mitigation)

An import link **never** writes a profile directly. It only opens a
dedicated, in-app **"Import server profile"** review screen. This screen is
the sole path by which an imported profile is persisted, and it enforces:

- **Full target disclosure.** The exact host and port (and fingerprint, if
  present) from the link are shown in full before any action is available.
  No field is elided, truncated in a way that hides the real host, or
  auto-filled into a state the user could mistake for "already trusted."
- **Explicit, distinct confirmation.** Persisting requires an explicit
  foreground tap on the review screen (e.g. "Add server"). No link
  parameter can auto-confirm, auto-navigate past this screen, or otherwise
  cause a profile to be saved without that tap — the same principle applied
  to notification-based permission approval in T2.
- **No silent overwrite.** If the imported host/port matches an existing
  stored profile (or the link otherwise targets an existing profile's
  identity), the screen does not present a generic "Add server" action. It
  switches to an explicit **"Update existing server?"** comparison view
  that shows the old and new host/fingerprint side by side, and requires a
  separate, distinctly labeled confirmation from "add new." A profile is
  never replaced as a side effect of importing a link with the same name or
  ID alone.
  This applies uniformly regardless of the single- vs. multi-profile
  decision (ADR 0005, OP3; see `docs/THREAT-MODEL.md` §1); in either mode,
  every existing profile is subject to the same no-silent-overwrite rule.
  V1 stores exactly **one** profile (OP3, ADR 0005), so an import that would
  create a second profile must be rejected with an explicit message rather
  than silently replacing or queueing behind the stored one.
- **One pending import at a time.** If a second import link arrives while a
  review screen is already pending confirmation, it does not queue behind
  or silently replace the pending one; the new link is discarded and the
  user must re-trigger the import if they intended the second one.
- **No bypass of first-connect identity verification.** Confirming an
  import does not substitute for the server-identity checks applied to
  manually entered profiles (T1) — TLS/fingerprint verification and the
  "server identity changed" warning apply identically to imported and
  manually entered profiles on first (and every subsequent) connection.

These requirements apply to every source of an import link: in-app QR
scanner, OS share sheet, and cold/warm app launch via a link.

## Permission approval confirmation (foreground + authenticated)

Resolves `docs/THREAT-MODEL.md` T2 and the open product decision on
"permission approval from a push notification."

A tool-call permission request is server state (`docs/THREAT-MODEL.md` §3,
flow 4): the OpenCode Server emits a request, the app surfaces it, and the
user's decision is sent back and executed on the user's machine. Because a
mistaken or spoofed "approve" directly authorizes code execution on that
machine, a bare notification action is never sufficient to finalize it.

### Notification role: surface, not authorize

- A pending permission request always triggers a local notification, but the
  notification carries **no inline "Approve" action**. It is informational
  and deep-links into the app's dedicated confirmation screen; the only
  action attached to it is "Deny," described below.
- Lock-screen / notification-shade quick actions never include "Approve."
  This holds regardless of OS (Android heads-up/quick-reply actions, iOS
  notification actions) and is not user-configurable back to "on" — see
  Rationale.
- "Deny" *is* offered as a lock-screen/notification action, because denying
  is the safe, reversible direction: it can, at most, cause the agent to ask
  again, never authorize anything. It still requires the device to be
  unlocked per normal OS notification-action behavior; it does not require
  foreground app confirmation, since it grants no capability.

### Mandatory foreground confirmation before approving (T2 mitigation)

Approving is only ever finalized from the in-app **confirmation screen**,
reached by tapping the notification or by opening the app directly (e.g.
from an in-app pending-approvals list). This screen enforces:

- **App in foreground.** The approve action is only enabled while the app is
  the foreground, focused activity/scene. If the app is backgrounded (a
  call comes in, the user switches apps, the OS shows a system dialog, or
  the screen locks) before the tap registers, the pending confirmation state
  is discarded, not queued — the user must re-open the request and start
  the review over. This defeats tapjacking/overlay attacks and OS quick
  actions that fire without a genuine foreground review, and stray taps
  that land after a background/foreground transition.
- **Full content disclosure.** The screen renders the complete command (or,
  for a file-write tool call, the full diff) the agent is requesting
  permission for — never a truncated summary, a title only, or a
  previously-cached rendering that might not match the current request
  content. Long content scrolls in place; it is not summarized or elided to
  fit the screen.
- **Explicit, distinct confirmation.** Approving requires an explicit tap on
  an "Approve" control on this screen. It is never the default/pre-focused
  action, is not triggered by a generic "OK"/dismiss gesture, and is
  visually and positionally distinct from "Deny" to reduce accidental-tap
  risk (same principle as the "distinct confirmation" rule for server
  profile import, T8).
- **Biometric re-authentication gate.** Confirming an approval additionally
  requires a platform biometric (or device-credential fallback) check via
  the `expect`/`actual` biometrics API, immediately before the decision is
  sent — not once per app session, once per approval. This is the default,
  not an opt-in; a settings toggle may relax it only down to "foreground
  confirmation without biometrics," never down to "notification-only."
  (This directly answers the open product decision: notification-only
  approval is not offered as a mode.)
- **Request-bound, single-use decision.** The confirmation screen carries
  the server-issued permission-request ID and a content hash of what it is
  displaying; the approve/deny call back to the server includes both. A
  request that was already decided, superseded, or whose content changed
  since it was fetched cannot be approved from a stale screen — the app
  re-fetches and re-renders before allowing the tap to submit (ties into
  T6's single-use/no-replay requirement on the same IDs).

### Rationale

Tapjacking/overlay spoofing on Android and lock-screen/notification-shade
quick actions on both platforms can trigger a notification action without
the user ever seeing, or genuinely consenting to, what they're approving.
Because a tool-call approval directly authorizes arbitrary code execution
on the user's own development machine (per T2's impact rating), it is
treated as a high-consequence, effectively irreversible action — the same
bar applied to persisting an imported server profile (T8) — rather than a
low-friction notification action.

## Local cache encryption at rest

The local SQLDelight cache (`docs/THREAT-MODEL.md` T3, B5) holds session
transcripts, prompts, and file diffs — potentially proprietary code — for the
disposable, offline-read-only store already fixed in `docs/TECH-STACK.md`.
It is never a second source of truth: it can be wiped and rebuilt from the
server at any time. This resolves the open decision flagged in `BOOTSTRAP.md`
and `docs/TECH-STACK.md` (Open Decisions #2) in favor of encrypting it.

### Requirements (mandatory)

- **Encrypted at rest, not relying on OS full-disk encryption alone.**
  Full-disk encryption only protects data while a device is powered off; it
  does nothing once the device is unlocked or a malicious/compromised app on
  the same device can reach the app's sandboxed storage — exactly the T3
  scenario (lost/stolen unlocked device, malware). The cache must not be
  recoverable in plaintext by reading the raw DB file off the device.
- **Excluded from unencrypted OS/cloud backups.** The cache DB (and its
  `-wal`/`-shm` companion files) must never end up in a plaintext-readable
  iCloud or Android auto-backup archive, independent of whether the DB
  itself is encrypted.
- **No user-facing passphrase.** The product has no separate app-level login
  or passcode — auth is Keychain/Keystore plus optional biometrics only (B2)
  — so the encryption key must be machine-generated and stored in the
  platform key store, never something the user types or remembers.
- **Key loss is a cache miss, not a fatal error.** Because the cache is
  disposable, a missing or invalidated key (e.g. a Keystore entry
  invalidated by biometric re-enrollment) must be handled by wiping and
  rebuilding the cache from the next server snapshot — never a crash or a
  manual-recovery flow for the user.

### Approach

- **Android**: back SQLDelight's Android driver with a SQLCipher-encrypted
  database (e.g. `net.zetetic:sqlcipher-android` via a
  `SupportSQLiteOpenHelper.Factory`). Generate a random passphrase on first
  run and store it through the platform's Keystore-backed secure storage —
  the same Keychain/Keystore `expect`/`actual` boundary already planned for
  server credentials (B2), not a separate mechanism.
- **iOS**: SQLCipher's iOS build requires replacing the system `libsqlite3`
  that SQLDelight's Kotlin/Native driver links against, which is a
  disproportionate native-toolchain dependency to take on before the KMP
  module scaffold (OPE-30) exists. Default instead to the platform's
  built-in Data Protection: create the cache database inside a location
  protected by `NSFileProtectionCompleteUnlessOpen` (or `.complete`, to be
  confirmed once offline/background read behavior is implemented), which
  ties decryption to the device passcode/Secure Enclave without the app
  managing its own key material. If SQLCipher-for-iOS later proves
  tractable within the KMP/CocoaPods toolchain, it may replace this for
  symmetry with Android — this decision sets the minimum bar, not a
  ceiling.
- **Both platforms**: exclude the cache DB and its `-wal`/`-shm` files from
  backups — Android via data-extraction/backup-exclusion rules
  (`android:dataExtractionRules`, within the already-fixed API 31+ floor)
  and iOS via `NSURLIsExcludedFromBackupKey` set on the file URL at
  creation time.

### Alternatives considered

- **Rely on OS full-disk encryption alone (status quo)** — rejected: it does
  not defend against the T3 scenario itself (unattended unlocked device,
  malware with app-sandbox access), which is exactly what app-level
  encryption is for.
- **SQLCipher on both platforms** — preferred in principle for one
  mechanism and one mental model, but the iOS native-linking cost is
  disproportionate this early; revisit once the module scaffold and iOS
  build tooling (OPE-30) exist.
- **User-set app passcode deriving the key** — rejected: it adds an
  authentication surface the product doesn't otherwise have, for a cache
  the architecture already treats as disposable and rebuildable.

### Status

Decision resolved; the driver wiring above is scoped for implementation once
the KMP/CMP module scaffold (OPE-30) and the shared/security `expect`/`actual`
layer exist. Treat this section as fixed guidance for that work, the same as
the T8 import-review flow above.

## Server identity verification (T1)

Resolves `docs/THREAT-MODEL.md` T1. Without a way to verify *which* OpenCode
Server it is talking to, the app cannot distinguish the user's own
self-hosted instance from a rogue server on the same LAN (malicious AP,
ARP/DNS spoofing) or a maliciously imported profile (T8). An impersonating
server can harvest the auth credential on first contact and then present
fake session/tool-call data, leading the user to approve attacker-crafted
tool calls believing they're reviewing their own agent's work.

### Requirements (mandatory)

- **Trust-on-first-use (TOFU) fingerprint verification, not CA-based
  validation, is the primary mechanism.** Self-hosted OpenCode servers on a
  LAN or Tailscale typically run with a self-signed certificate or no TLS at
  all; requiring a publicly-trusted CA certificate would push most users
  either to plaintext HTTP or to disabling certificate validation outright —
  both worse than TOFU. This mirrors the model users already understand from
  SSH host keys.
- **Fingerprint = SHA-256 of the leaf certificate's SubjectPublicKeyInfo
  (SPKI)**, computed at TLS handshake time. It is stored per server profile
  in the same Keychain/Keystore boundary already used for credentials (B2) —
  not a separate mechanism — so fingerprint trust is isolated per profile the
  same way T8 requires credential/identity isolation between profiles.
- **Identity is verified before the credential is ever sent.** The trust
  check (pinned-fingerprint match, or a fresh TOFU acceptance) must complete
  before the adapter attaches the `Authorization` header to any request on
  that connection — on first connect as much as on every reconnect. It must
  not be possible for a request carrying the credential to reach the network
  before this check passes.
- **First connect**: the exact fingerprint is shown to the user (rendered as
  colon-separated hex, the same convention as SSH/TLS tooling), and
  persisting it as the profile's pinned value — and sending the credential
  for the first time — requires an explicit, distinct foreground
  confirmation. This is the same "explicit, distinct confirmation" bar
  applied to import review (T8) and permission approval (T2); a generic
  "OK"/dismiss gesture does not count.
- **Every subsequent connect**: the presented fingerprint is compared to the
  pinned value before the handshake completes. On mismatch, the app **fails
  closed**: it does not fall back to an unverified connection, does not offer
  a low-friction "trust anyway" option alongside the normal reconnect flow,
  and does not silently retry. It shows a full-screen, non-dismissible-by-
  swipe "server identity changed" warning — visually and textually distinct
  from a generic connection error — stating this may indicate the server has
  been replaced or is being impersonated, and requires the user to explicitly
  review the new fingerprint and re-confirm (functionally a new TOFU
  acceptance) before any further request, including a retry, is sent.
- **Plaintext HTTP profiles get a persistent, explicit warning, not an
  icon.** If a profile has no TLS, there is no certificate to pin — the app
  cannot verify server identity or protect the credential in transit at all.
  This is disclosed with visible text (not solely a small padlock-style
  icon) at profile creation and every time that profile is used to connect,
  stating plainly that the credential and all traffic are readable to
  anyone on the network path. Whether plaintext HTTP is restricted to
  private LAN/Tailscale address ranges by default is a connection-policy
  question left to OPE-5/the L1 connection design, not decided here.
- **Imported profiles are not exempt.** Per the T8 section above, an import
  link may optionally carry a fingerprint, but confirming an import never
  bypasses this section's checks — first-connect display and every-connect
  verification apply identically regardless of how the profile was created.

### Approach

- The tech stack (`docs/TECH-STACK.md`) fixes Ktor as the HTTP/SSE client
  underlying `OpenCodeV2Adapter`/`EventProcessor`. Ktor has no single
  cross-platform certificate-pinning API, so the check is implemented at the
  platform engine layer behind the same `expect`/`actual` boundary already
  used for Keychain/Keystore and biometrics — the adapter itself stays
  platform-agnostic.
  - **Android (OkHttp engine)**: a custom `X509TrustManager` (or a
    per-profile-reloaded OkHttp `CertificatePinner`) computes the SPKI
    SHA-256 of the presented leaf certificate and compares it to the
    profile's pinned value before the handshake completes.
  - **iOS (Darwin engine)**: a `URLSession` challenge delegate
    (`urlSession(_:didReceive:completionHandler:)`) performs the equivalent
    SPKI comparison against the chain from `SecTrustCopyCertificateChain`
    before allowing the connection to proceed.
- Credential attachment (the `Authorization` header interceptor) is
  structured to depend on "this connection has passed the identity check,"
  rather than being independently wired — so it is not possible to
  accidentally reorder the two and leak the credential on an unverified
  connection.

### Alternatives considered

- **CA-based TLS validation only** — rejected as the sole mechanism: see
  Requirements above; it doesn't fit the self-hosted, typically self-signed
  or plaintext LAN deployment this product targets.
- **A single certificate/key hardcoded in the app** — not applicable: there
  is no one OpenCode Server instance to pin against. Each user runs their
  own, so trust has to be established per profile at pairing time (TOFU),
  not baked into the app binary.
- **Soft/dismissible warning on fingerprint mismatch (e.g. a toast)** —
  rejected: a dismissible warning is exactly the failure mode T1 describes —
  the user waves through an impersonator without a genuine review. The
  warning must block further requests, not merely inform.

### Status

Implemented (OPE-35) on top of the KMP module scaffold (OPE-30) and the
OpenAPI-generated client (OPE-31):

- `shared/domain` defines the `ServerProfile`, `ServerFingerprint` (SHA-256
  SPKI), `ServerIdentityCheck`, and the `ServerIdentityStore` /
  `ServerIdentityVerifier` ports.
- `shared/security` implements the TOFU decision logic
  (`TofuServerIdentityCoordinator` / `ServerIdentityGate`), the shared DER
  SPKI extraction, the plaintext-HTTP warning text, and the platform pieces:
  Android Keystore-backed pin storage plus an OkHttp `X509TrustManager`;
  iOS Keychain-backed pin storage plus a `URLSession` challenge delegate.
- `shared/networking` implements `OpenCodeV2Adapter.connect` so the credential
  permit only exists after the gate returns `Authorized`; the platform TLS
  engine additionally enforces the pin during the handshake.
- Regression tests cover first contact, pinned match, mismatch, explicit
  re-confirmation, plaintext warnings, fingerprint formatting, and DER SPKI
  extraction (`shared/security/src/commonTest`, `shared/networking/src/commonTest`).
- Real-handshake validation (OPE-94) runs the platform pinning engines against
  a live self-signed TLS peer, on an Android emulator and an iOS simulator, and
  asserts the three T1 acceptance cases: TOFU capture, fail-closed on a
  certificate swap with no request carrying the `Authorization` header reaching
  the server, and a successful reconnect on a matching pin. The fixtures live in
  `shared/tls-test-support`; the Android path uses an in-process
  `SSLServerSocket` peer, while the iOS path uses the host-side recording server
  `tests/t1/tls_recording_server.py` (a `SecIdentity`/`NWListener` TLS server is
  not exposed by public Kotlin/Native iOS APIs). Both run from shell steps in
  `.github/workflows/t1-device-validation.yml`, because the org Actions
  allowlist permits only `actions/checkout@*`.

Because this touches shared/security and the adapter/generated-client boundary,
the implementing PR requires the reinforced review path (Code Reviewer +
Security Engineer + explicit owner approval) per `BOOTSTRAP.md`.

## Speech-to-text dictation and on-device enforcement (T5)

Resolves `docs/THREAT-MODEL.md` T5 (B3, Data flow #3). Dictation allows users to
speak prompts (which may contain proprietary code, file paths, or credentials)
into the composer for transmission to their OpenCode Server. Mobile platform
speech-to-text APIs often default to cloud-assisted recognition (routing audio to
Apple or Google servers), which would silently violate the product's fundamental
no-telemetry promise.

### Requirements (mandatory for Version 1)

- **Strictly on-device speech recognition in V1.** Dictation must use exclusively
  on-device recognition APIs. No audio or transcript may leave the device toward
  any third party (Apple, Google, etc.).
- **iOS enforcement**:
  - Check `SFSpeechRecognizer.supportsOnDeviceRecognition` for the active locale.
  - Explicitly set `SFSpeechAudioBufferRecognitionRequest.requiresOnDeviceRecognition = true`
    on every recognition request. If on-device recognition is unsupported or models
    are missing, the request must fail closed rather than falling back to cloud.
- **Android enforcement**:
  - Instantiate recognizers strictly via `SpeechRecognizer.createOnDeviceSpeechRecognizer(Context)`
    (API 31+ project floor). Never use `SpeechRecognizer.createSpeechRecognizer(Context)`.
  - Do not rely on `RecognizerIntent.EXTRA_PREFER_OFFLINE` as an enforcement mechanism,
    as Android documentation defines it as a non-binding hint.
  - Verify runtime availability via `SpeechRecognizer.isOnDeviceRecognitionAvailable(Context)`
    (API 33+) or handle `ERROR_LANGUAGE_NOT_SUPPORTED` / `ERROR_LANGUAGE_UNAVAILABLE`
    as an unavailable state without network fallback.
- **UX when unavailable**:
  - If on-device recognition is unavailable for the current device/OS/locale, the
    mic button in the composer is disabled (not hidden) with a clear message:
    "Dictation isn't available on this device for [language]. Type your prompt instead."
  - The keyboard remains the standard fallback input method.
- **Privacy policy disclosure**: The privacy policy (owned by Technical Writer)
  must explicitly state that V1 dictation is processed strictly on-device and
  disabled when on-device recognition is unavailable.

### Extensibility & Future Evolution (Post-V1)

While Version 1 strictly mandates on-device platform recognition, the application
architecture must remain extensible for future evolution beyond V1:

- **Interface abstraction**: The shared platform layer defines a clean
  `DictationProvider` interface that yields a closed availability model
  (`Available`, `Unavailable(reason)`). The composer interacts solely with this
  interface.
- **V1 implementation**: Only `PlatformOnDeviceDictationProvider` is active in V1,
  guaranteeing zero network-fallback capability in the shipped binary.
- **Post-V1 evolution options**:
  1. *Server-side OpenCode transcription*: A future release may allow the user's
     self-hosted OpenCode server to transcribe audio (e.g. via local Whisper on
     the host). Spoken audio is transmitted solely over the existing authenticated
     TLS connection (B1) to the user's server, preserving zero third-party
     telemetry while expanding language/device support.
  2. *User-configured external endpoints*: Users may optionally configure custom
     STT services with their own API credentials, gated by explicit opt-in and
     prominent privacy notices.

### Status

Decision resolved for Version 1; binding guidance for lot L4 ("Mobile
integration") implementation per `ROADMAP.md`. See full specification in
`docs/adr/on-device-speech-to-text.md`.

## HTTP logging redaction (T4)

Implements `docs/THREAT-MODEL.md` T4 (B7). Verbose Ktor HTTP logging in the
generated OpenAPI client or the `OpenCodeV2Adapter` must never emit the server
auth token or full prompt bodies, because those logs may be captured by
logcat/sysdiagnose, crash reporters, or shared bug reports.

### Requirements (mandatory)

- **Redaction is structural, not call-site discipline.** The redaction lives
  inside the logger and the plugin configuration, so a future call site cannot
  regress it by forgetting to scrub a value.
- **Credential headers are never logged verbatim.** `Authorization` (and
  cookies, API-key headers, session tokens) are redacted at the Ktor
  `sanitizeHeader` boundary and again in the logger.
- **Sensitive JSON body fields are never logged verbatim.** Prompt/message
  content, credentials, and diff/patch payloads are replaced with a marker;
  structural metadata (method, URL, status, content type, size) is preserved.
- **One sanctioned logging entry point.** HTTP logging in `shared/networking` /
  `shared/data` is enabled only through `installSanitizingLogging`, which wires
  `SanitizingHttpLogger` and the header sanitizer.

### Implementation

- `shared/networking/src/commonMain/kotlin/org/opencodemobile/shared/networking/logging/LogRedactor.kt`
  — pure redaction rules (sensitive headers, bearer/basic credentials, sensitive
  JSON keys), with no logging side effects so it is exhaustively testable.
- `.../logging/SanitizingHttpLogger.kt` — Ktor `Logger` that routes every line
  through `LogRedactor.redact` before emitting it.
- `.../logging/SanitizingLogging.kt` — `HttpClientConfig.installSanitizingLogging`
  extension that installs Ktor's `Logging` plugin with the sanitizing logger and
  a `sanitizeHeader` predicate; the only supported way to turn on HTTP logging.
- Tests: `shared/networking/src/commonTest/.../logging/LogRedactorTest.kt` and
  `SanitizingHttpLoggerTest.kt` assert that a fixture request carrying a fake
  `Authorization: Bearer sk-test-…` header and a fake prompt body produces log
  output that contains neither value, even at `LogLevel.ALL`.

### Enforcement (CI, not just review)

- `scripts/check-no-secret-logging.sh` is a JDK-free static gate: it fails if any
  production source under `shared/networking` / `shared/data` (outside the
  sanctioned `logging/` package and generated code) installs the raw `Logging`
  plugin, uses a verbose `LogLevel`, or logs an `Authorization`/`Bearer` value or
  a raw request/response body.
- `.github/workflows/security-logging.yml` runs that gate and the redaction unit
  tests on every PR touching those modules; it is intended to be a **required**
  check in branch protection so the PR policy's "secret in logs" rejection
  criterion is machine-enforced.

### Status

Implemented for the scaffolded `shared/networking` module; wired into CI. Any
future `OpenCodeV2Adapter` HTTP client must obtain its `HttpClient` from a
configuration that calls `installSanitizingLogging`; the CI gate fails the build
otherwise.

---

## 8. Architecture decision records

| ADR | Decision |
|---|---|
| [0001](adr/0001-monorepo-module-scaffold.md) | Monorepo module scaffold: namespace, toolchain versions, one iOS framework per module, `iosApp` as a thin Swift host, `shared/domain` stdlib-only. |
| [0002](adr/ADR-0002-opencode-v2-api-surface-mapping.md) | OpenCode v2 API surface mapping: root surface is canonical, async prompt only, directory scoping, generated-client isolation (R3). |
| [0003](adr/0003-liquid-glass-vs-pure-cmp-ios.md) | Pure Compose Multiplatform UI on iOS, no native Liquid Glass chrome; CMP owns navigation and lifecycle. |
| [0004](adr/0004-architecture-dependency-rules-konsist.md) | Architecture/dependency rules enforced by Konsist in CI. |
| [0005](adr/0005-v1-open-decisions-op1-op5.md) | Closeout of the V1 open decisions OP1–OP5, and the MVP cut that follows from them. |
| [on-device-speech-to-text](adr/on-device-speech-to-text.md) | On-device-only dictation in V1, with a fail-closed availability model. |

Anything not literally fixed by the architecture specification goes through an
ADR, per the specification's own rule.

## 9. Open decisions

All five V1 open decisions are **decided** by
[ADR 0005](adr/0005-v1-open-decisions-op1-op5.md) (2026-09-29). OP2 is closed on
Android and accepted-with-residual-risk on iOS (see §"Local cache encryption at
rest"); no decision is left open. The table is kept as the traceability index,
not as a work list.

| # | Decision | Decision taken | Binding design |
|---|---|---|---|
| OP1 | Liquid Glass vs. pure Compose Multiplatform on iOS | Pure CMP UI in V1, no native Liquid Glass. iOS POC still required in L1. | [ADR 0003](adr/0003-liquid-glass-vs-pure-cmp-ios.md) |
| OP2 | Local cache encryption | Encrypted at rest. Android SQLCipher + Keystore-held passphrase (**closed**); iOS Data Protection as the V1 bar, SQLCipher-for-iOS deferred — **accepted residual risk** (not covered: sandbox malware, unlocked stolen device). Lands with the cache in L2. | §"Local cache encryption at rest" above, T3 |
| OP3 | Single vs. multiple server profiles in V1 | **One** stored profile. `ServerId` is kept in the Domain model, and the cache stays scoped `ServerId → ProjectId → SessionId`, so multi-profile remains a data-layer change. No profile-management UI in V1; multi-profile is Post-V1 P1. | ADR 0005 §2 ; ARCHITECTURE §3.4 ; T8 |
| OP4 | Approving permissions from a lock-screen notification | **Not offered in V1.** Notifications are a read-only surface for approvals — at most a "Deny" action, never "Approve"; approval requires the foregrounded, authenticated confirmation screen. | §"Permission approval confirmation (foreground + authenticated)" above, T2 |
| OP5 | `PROPOSED`-only requirements in the specification | No bulk validation. Each `PROPOSÉ` item keeps its status until an explicit individual decision. V1-07 (agent questions) and V1-08 (abort) are in the MVP cut **by explicit decision**, not by bulk validation. | ADR 0005 §2, §4 |

## 10. Known gaps

- **Build verification of the scaffold is static only** so far: the scaffold PR
  states that no Gradle build was executed in its sandbox. CI on the merge path
  is the first real confirmation (ADR 0001).
- **iOS project wiring** (`.xcodeproj`, per-module framework linking, SwiftUI vs.
  CMP host integration) is not yet generated; ADR 0001 §4 covers the reason and
  ADR 0003 fixes the target model.
- **`shared/*` modules are scaffolds**: `shared/domain` exposes the connection
  ports, `shared/security` implements T1, and log redaction is real, but the
  application, data, realtime, persistence, and feature modules are placeholder
  objects. Their internals are specified in §2–§3 and implemented per `ROADMAP.md`
  lots L1–L5, gated by the commanditaire's sign-off.
- **Contract testing against a real server** requires a running instance; the
  deterministic `MockOpenCodeServer` (OPE-32) is the CI-safe half.
- This document must be revised whenever a component boundary, transport, or
  deployment path changes; the security sections additionally follow the
  threat model's review policy.
