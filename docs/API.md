# API Reference — OpenCode Server v2

OpenCode Mobile has no API of its own. Its entire external contract is the
**OpenCode Server v2** HTTP + SSE API, which runs on the user's own machine.
This document describes the surface the app consumes, the authentication
model, and how the typed client is generated and kept isolated.

The endpoint mapping was verified against a live server and recorded in
[ADR 0002](adr/ADR-0002-opencode-v2-api-surface-mapping.md).

## Pinned specification

| Field | Value |
|---|---|
| Server | OpenCode Server v2 `1.18.32` |
| OpenAPI | `3.1.0` |
| Pinned on | 2026-09-24 |
| Source | `GET /doc` on a live `opencode serve` instance |
| Vendored spec | `shared/networking/openapi/opencode-server-v2.json` |
| SHA-256 | `46db986090aae41846cd6dbe16225a1d883f0bbcb4c48814008d3f6ce140aa5c` |

The spec is vendored so builds are reproducible and do not depend on a running
server. See [`shared/networking/openapi/README.md`](../shared/networking/openapi/README.md)
for the re-vendoring procedure.

## Authentication

OpenCode Server v2 accepts an optional password via `OPENCODE_SERVER_PASSWORD`.
When it is set, every request must carry either:

- `Authorization: Bearer <password>`, or
- `Authorization: Basic base64("opencode:<password>")`.

The `/event` SSE stream honors the same header. The app injects the
credential on all requests after the server identity check passes, and stores
the credential only in Android Keystore / iOS Keychain. It is never placed in
a deep link or QR code.

## Surfaces: root vs `/api/`

The spec exposes two surfaces:

- **Root surface** (`/session`, `/permission`, `/question`, `/event`,
  `/global/health`, `/config`, `/provider`) — the mature, canonical surface
  used by the OpenCode TUI and web UI.
- **Experimental `/api/` surface** — the emerging TypeScript HttpApi surface;
  some endpoints are still marked experimental.

**Decision:** `OpenCodeV2Adapter` standardizes on the **root surface** for all
core operations. The `/api/*` variants are reference material only.

## Endpoints used

### Health and handshake

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/global/health` | Returns `{"healthy": true, "version": "..."}`. Used for the initial handshake and `CompatibilityProfile` gate. Requires no session. |

### Events (realtime)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/event` | Server-Sent Events (`text/event-stream`) for the active workspace. The single `EventProcessor` subscribes here. |

### Sessions

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/session` | List sessions. |
| `POST` | `/session` | Create a session. |
| `GET` | `/session/{sessionID}` | Fetch one session. |
| `PATCH` | `/session/{sessionID}` | Update a session. |
| `DELETE` | `/session/{sessionID}` | Delete a session. |
| `POST` | `/session/{sessionID}/fork` | Fork a session. |
| `POST` | `/session/{sessionID}/abort` | Interrupt the active turn. |

`sessionID` follows the server pattern `^ses`.

### Messages and prompts

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/session/{sessionID}/message` | List messages. |
| `GET` | `/session/{sessionID}/message/{messageID}` | Fetch one message. |
| `POST` | `/session/{sessionID}/prompt_async` | Send a prompt (fire-and-forget, returns `204`). |

**Decision:** the mobile client never uses a blocking prompt endpoint. All
prompts go through `prompt_async`, and incremental output is consumed from
`/event`. This keeps the app resilient on flaky mobile networks.

### Status (polling fallback)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/session/status` | Returns `idle`, `busy`, or `retry`. Used when the SSE stream is unavailable. |

### Permissions

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/permission` | List active permission requests (pattern `^per`). |
| `POST` | `/permission/{requestID}/reply` | Reply with `{"reply": "once" \| "always" \| "reject", "message": "..."}`. |

An optional `directory` query parameter scopes the request to the active
project root, avoiding cross-project contamination when one server hosts
multiple directories.

### Questions

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/question` | List pending questions. |
| `POST` | `/question/{requestID}/reply` | Answer with `{"answers": [["label1"], ...]}`. |
| `POST` | `/question/{requestID}/reject` | Reject with an empty body. |

Both accept an optional `directory` query parameter.

### Projects

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/project` | List projects. |
| `GET` | `/project/current` | Active project. |
| `GET` | `/project/{projectID}` | Fetch one project. |
| `GET` | `/project/{projectID}/directories` | Project directories. |
| `PATCH` | `/project/{projectID}` | Update a project. |

### Config, providers, agents, models

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/config` | Server configuration. |
| `GET` | `/config/providers` | Configured providers. |
| `GET` | `/provider` | Providers with their model lists. |
| `GET` | `/provider/auth` | Provider authentication state. |
| `GET` | `/agent` | Available agents. |
| `GET` | `/api/model` | Model listing (experimental surface). |

## Client generation

The typed client is generated from the pinned spec and wired into the Gradle
build:

```bash
./gradlew :shared:networking:generateOpenApiClient
```

Generated code lives at
`shared/networking/src/commonMain/kotlin/org/opencode/mobile/networking/client/generated/`
and must never be hand-edited. To change the contract, re-vendor the spec and
regenerate.

## Boundary rules

- **R3:** generated OpenAPI types must never be imported outside
  `shared/networking`. `OpenCodeV2Adapter` translates generated DTOs into
  `shared/domain` types and is the sole consumer of the generated client.
- **R12:** generated client code is never manually edited; it is always
  regenerated from the pinned spec.
- **R13:** every protocol assumption and version idiosyncrasy stays inside
  `OpenCodeV2Adapter`.

`./scripts/verify-r3-generated-types.sh` enforces R3 in CI.

## Error handling

Transport and protocol failures are translated into typed `DomainError`
values at the adapter boundary, so no Ktor or generated type crosses into
`shared/domain`, `shared/application`, or the UI. See
[ARCHITECTURE.md](ARCHITECTURE.md).