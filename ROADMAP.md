# OpenCode Mobile — Roadmap

_Owner: Product Owner. Source of truth for scope: "OpenCode Mobile — Cahier des charges d'architecture v1.0 (convergé)" (attached to OPE-2). This roadmap translates that spec into sequenced, gated milestones. Any change to milestone content or order goes through an ADR, per the spec's own rule._

## Vision

OpenCode Mobile is a native, independent, open-source mobile remote control (Android + iOS) for a self-hosted OpenCode Server v2. It lets a solo developer start/resume coding sessions, watch agent work in real time, approve or deny tool-call permissions, answer agent questions, dictate prompts, and review files/diffs — without reopening a laptop. It is explicitly **not** a mobile IDE, not a local LLM runtime, not a cloud service/proxy, and not a reimplementation of OpenCode. The OpenCode Server remains the sole source of truth; there is no product backend and no code/prompt telemetry.

This is a **specification-execution project, not a discovery project**: architecture, stack, and design are already decided and documented. The Product Owner's role here is sequencing and backlog health, not re-deriving requirements.

## Delivery model

Delivery is split into **7 sequential lots (L0–L6)**. Each lot ends with an explicit validation gate from the commanditaire (project owner) before the next lot starts — no lot may begin early, and no lot is "done" without that sign-off. Lots are ordered by dependency; none are dated.

All code changes ship via PR (no direct commits to `main`), with review intensity scaled to the area touched (see `AGENTS.md`/`CLAUDE.md` rules R1–R15 and the reinforced-review list in the company brief: security, the OpenCode adapter/generated client, realtime, permissions, CI/CD & release signing).

## Milestones

| Lot | Name | Scope | Key requirements | Exit gate |
|:---|:---|:---|:---|:---|
| **L0** | Bootstrap | Monorepo + module skeleton (§5), CI on every PR, architecture/dependency tests, OpenCode OpenAPI spec pinned + generated client, baseline `MockOpenCodeServer`, i18n scaffolding, design-system module skeleton, ADR for third-party libraries (Markdown/syntax highlighting), Liquid Glass vs. pure Compose Multiplatform decision (OP1) | D3–D5, D10–D13 | CI green; dependency graph validated |
| **L1** | Connection | SecureStore, handshake (health → version → `CompatibilityProfile`), manual entry + versioned QR, HTTP policy (LAN/Tailscale allowed, public HTTP rejected), typed `DomainError`, incompatible-server screen | V1-01…V1-03, D7 | LAN + Tailscale connection recipe passes |
| **L2** | Realtime + sessions | `EventProcessor` (SSE + polling fallback + backoff), snapshot/reconciliation sequence, SQLDelight cache, read-only offline, session CRUD + fork | V1-04, D2, D6, D8, D9 | Kill the app mid-turn → state rebuilt from the server |
| **L3** | Chat + permissions | Streaming transcript, Markdown/code rendering, composer, abort, non-dismissable permission banner, pending questions, model/agent listing | V1-05…V1-09 | No permission lost across Mock scenarios |
| **L4** | Mobile integration | OS dictation, local notifications, optional biometrics, task-switcher masking, "erase everything" | V1-10, V1-13 | Real-device recipe passes on Android + iOS |
| **L5** | Files + diffs | File navigation, read-only viewer, unified diff viewer | V1-11, V1-12 | 5 MB file viewer stays smooth |
| **L6** | Hardening + release | Performance targets (§10.1), server compatibility matrix, best-effort accessibility, threat-model review, store listings + non-affiliation disclaimer, TestFlight/Play Internal → public | §10, §11, §13 | Full V1 Definition of Done met; publication approved |
| **Post-V1** | P1 backlog | Light file editing behind a flag, interactive terminal, mDNS discovery, attachments/deep links, multi-server profiles | §3.2 | One ADR per feature |

Five decisions remain open and should be closed no later than L0/L1: Liquid Glass on iOS (OP1), local cache encryption (OP2), single vs. multiple server profiles in V1 (OP3), approving permissions from a lock-screen notification (OP4), and the batch of `PROPOSED`-only requirements in the spec (OP5).

## Current status (2026-09-24)

- Repository: initialized with a single bootstrap commit only. No code, no CI, no GitHub remote yet — we are at the very start of **L0**.
- The generic bootstrap backlog (tech-stack ADR, initial architecture doc, design system, threat model, security review, GitHub repo init, branch protection, CI/CD, linter, tests, build, deploy) already exists as issues (OPE-3…OPE-20) and covers the *decision/documentation* half of L0. Most are currently `blocked` on an unrelated workspace-provisioning issue, not on missing scope — that is being worked separately and is not a backlog-content problem.
- This roadmap adds the *implementation* half of L0 that wasn't yet represented in the backlog: actually scaffolding the module skeleton, pinning the OpenAPI spec and generating the client, standing up `MockOpenCodeServer`, enforcing dependency rules in CI, and resolving the Liquid Glass decision. See "First backlog batch" below.

## First backlog batch (generated from this roadmap)

Created unassigned, `todo`, tagged for auto-assign to Engineer, all scoped to L0:

1. Scaffold the KMP/CMP monorepo module structure (§5.1)
2. Pin the OpenCode Server v2 OpenAPI spec and generate the API client
3. Build a baseline deterministic `MockOpenCodeServer` for tests
4. Add CI architecture/dependency-rule tests for module boundaries (§5.2)
5. Record an ADR for the Liquid Glass vs. pure Compose Multiplatform decision (OP1)

## Backlog health process

- Target: at least 3 unassigned `todo` issues at all times, covering the active lot.
- When the count drops below 3, the Product Owner reviews progress against this roadmap and generates the next batch from the current or next lot — never from a future lot before its predecessor's gate has passed.
- Milestone gates in the table above are the acceptance criteria for "the lot is done" — a lot doesn't close on code merged, it closes on the commanditaire's explicit sign-off.
