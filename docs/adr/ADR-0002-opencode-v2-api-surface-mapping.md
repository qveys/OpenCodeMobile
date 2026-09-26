# ADR-0002: OpenCode Server v2 API Surface Mapping & §6.1 Specification Verification

- **Status**: Accepted
- **Date**: 2026-09-24
- **Author**: Engineer
- **Related Issue**: OPE-31 (Pin OpenCode Server v2 OpenAPI spec and generate the API client)
- **References**:
  - Cahier des charges v1.0 (§5, §6.1, §14.2, D5, R3, R13)
  - Vendored OpenAPI Specification: `shared/networking/openapi/opencode-server-v2.json`
  - Target Server Version: OpenCode Server v2 (`1.18.32`), OpenAPI `3.1.0`

---

## 1. Context and Problem Statement

In the architecture specification (*Cahier des charges d'architecture v1.0*, §6.1 and §14.2), the API endpoints assumed to be exposed by OpenCode Server v2 were explicitly tagged as **SUPPOSÉ, à vérifier contre la spec** (Hypothesized, to be checked against the spec).

Under OPE-31, we pinned the official, versioned OpenAPI 3.1.0 specification for OpenCode Server v2 (`v1.18.32`, SHA-256 `46db986090aae41846cd6dbe16225a1d883f0bbcb4c48814008d3f6ce140aa5c`) extracted directly from `GET /doc` on a live instance.

This Architectural Decision Record:
1. Performs the formal contract comparison between the §6.1 assumed endpoints and the real OpenAPI 3.1.0 surface.
2. Identifies exact parameter shapes, method semantics, and nuances (such as dual root vs. `/api/` endpoints).
3. Defines the binding architectural decisions for how `OpenCodeV2Adapter` must consume the generated client while insulating Domain and UI from generated types (Rule R3, R13).

---

## 2. Comparison: Assumed §6.1 Endpoints vs. Verified OpenAPI Spec

The table below contrasts each capability assumed in §6.1 against the concrete OpenAPI 3.1.0 endpoints:

| Capability | Assumed in §6.1 | Verified Endpoint(s) in OpenAPI Spec | Status / Alignment | Nuance & Decision |
|---|---|---|---|---|
| **Santé (Health)** | `GET /global/health` and/or `/api/health` | `GET /global/health`<br>`GET /api/health` | **Exact Match** | `GET /global/health` returns `{"healthy": true, "version": "1.18.32"}` and requires no session context. Use `/global/health` for initial handshake and `CompatibilityProfile` gating. |
| **Événements (Events)** | `GET /event` and/or `/api/event` | `GET /event`<br>`GET /api/event`<br>`GET /global/event`<br>`GET /api/session/{sessionID}/event` | **Exact Match** | `GET /event` provides an SSE (`text/event-stream`) for the active workspace instance. Central `EventProcessor` subscribes to `GET /event`. |
| **Sessions** | `GET/POST /session`<br>`GET /session/:id` | `GET /session`<br>`POST /session`<br>`GET /session/{sessionID}`<br>`PATCH /session/{sessionID}`<br>`DELETE /session/{sessionID}`<br>`POST /session/{sessionID}/fork`<br>`POST /session/{sessionID}/abort` | **Exact Match + Extensions** | Path parameter naming is `{sessionID}` (with pattern `^ses`). Server supports `/session/{sessionID}/fork` and `/session/{sessionID}/abort` natively. |
| **Messages & Prompt** | `GET messages`<br>`POST prompt / prompt_async` | `GET /session/{sessionID}/message`<br>`POST /session/{sessionID}/prompt_async`<br>`POST /api/session/{sessionID}/prompt`<br>`GET /session/{sessionID}/message/{messageID}` | **Clarified / Specific** | Root API exposes `POST /session/{sessionID}/prompt_async` taking `parts` array (returning HTTP 204 No Content). Responses stream through `/event` SSE. Use `prompt_async` for non-blocking mobile execution. |
| **Statut (Status)** | `GET /session/status` | `GET /session/status` | **Exact Match** | Returns `anyOf: [idle, busy, retry]`. Used by polling fallback during busy turns (§6.2). |
| **Permissions** | `GET /permission`<br>`POST reply` | `GET /permission`<br>`POST /permission/{requestID}/reply`<br>`GET /api/session/{sessionID}/permission`<br>`POST /api/session/{sessionID}/permission/{requestID}/reply` | **Clarified Parameters** | Root `GET /permission` returns active requests (`^per`). `POST /permission/{requestID}/reply` accepts `{ "reply": "once" \| "always" \| "reject", "message": "..." }` and optional query param `directory`. |
| **Questions** | `GET /question`<br>`POST reply/reject` | `GET /question`<br>`POST /question/{requestID}/reply`<br>`POST /question/{requestID}/reject`<br>`POST /api/session/{sessionID}/question/{requestID}/reply`<br>`POST /api/session/{sessionID}/question/{requestID}/reject` | **Exact Match** | `POST /question/{requestID}/reply` takes `{ "answers": [["label1"], ...] }`. `POST /question/{requestID}/reject` takes empty body. Query param `directory` scopes to workspace. |
| **Projets (Projects)** | `GET /project` | `GET /project`<br>`GET /project/current`<br>`GET /project/{projectID}`<br>`GET /project/{projectID}/directories`<br>`PATCH /project/{projectID}` | **Exact Match + Extensions** | Multiple projects supported. `GET /project/current` returns the active project. Directory list available via `/project/{projectID}/directories`. |
| **Config / Modèles** | `GET /config`<br>`GET /provider` | `GET /config`<br>`GET /config/providers`<br>`GET /provider`<br>`GET /provider/auth`<br>`GET /api/provider`<br>`GET /api/model`<br>`GET /agent` | **Exact Match + Extensions** | Models listed via `GET /provider` (returning providers with model lists) and `GET /api/model`. Agents listed via `GET /agent`. |

---

## 3. Key Findings & Architectural Nuances

### 3.1 Dual Surface: Root vs. `/api/` Endpoints
The OpenCode Server v2 OpenAPI specification contains two distinct API surfaces:
1. **Root Surface** (`/session`, `/permission`, `/question`, `/event`, `/global/health`, `/config`, `/provider`):
   - This is the mature, production surface used by the official OpenCode TUI, web UI, and test harness.
   - It supports `prompt_async` (`POST /session/{sessionID}/prompt_async`), which immediately dispatches work without holding the HTTP connection open, streaming incremental progress over the `/event` SSE stream.
   - Root permissions and questions endpoints (`/permission`, `/question`) accept an optional `directory` query parameter to filter by active repository path.
2. **Experimental `/api/` Surface** (`/api/session`, `/api/health`, `/api/permission/request`, etc.):
   - Represents the emerging TypeScript HttpApi surface.
   - Some endpoints remain marked experimental.

**Decision**: `OpenCodeV2Adapter` will standardize on the **Root Surface** for all core operations (handshake, sessions, async prompt, abort, permissions, questions, SSE events) as it is the canonical, battle-tested contract. The `/api/*` variants will serve as fallback reference if needed.

### 3.2 Asynchronous Prompt vs. Synchronous Blocking Prompt
- `POST /session/{sessionID}/prompt_async` is fire-and-forget: it returns `204 No Content` as soon as the prompt is accepted by the server.
- The assistant's reasoning, tool executions, questions, permission requests, and text chunks are all streamed sequentially through `GET /event` (SSE).
- If the user cancels the generation, `POST /session/{sessionID}/abort` interrupts the turn.

**Decision**: The mobile client will **never** use blocking prompt endpoints over HTTP. All prompts will be sent via `POST /session/{sessionID}/prompt_async`, relying exclusively on the central `EventProcessor` to process SSE event updates. This matches Rule R9 and ensures resilience over flaky mobile networks.

### 3.3 Scoping with `directory` Query Parameter
- OpenCode Server v2 can manage sessions across multiple directories or worktrees.
- Endpoints `GET /permission`, `POST /permission/{requestID}/reply`, `GET /question`, `POST /question/{requestID}/reply` support an optional `directory` parameter.

**Decision**: When connecting to a server, `OpenCodeV2Adapter` will pass the active project root directory when querying permissions and questions to avoid cross-project contamination if multiple directories are open on the same server instance.

### 3.4 Authentication Scheme
- OpenCode Server v2 accepts `OPENCODE_SERVER_PASSWORD`.
- If set, incoming HTTP requests must include either:
  - `Authorization: Bearer <password>`, or
  - Basic Authentication: `Authorization: Basic base64(opencode:<password>)`.
- SSE streams on `/event` also honor the `Authorization` header.

**Decision**: `OpenCodeV2Adapter` and its Ktor HTTP transport will inject `Authorization: Bearer <token>` on all requests when a server password is configured, and will store the token exclusively in platform secure storage (Keychain/Keystore per Rule R6).

---

## 4. Layer Isolation & Rule R3 Enforcement

The specification mandates:
> *Rule R3: Never expose generated OpenCode API types outside the Data layer.*

To enforce this:
1. All generated API classes, request bodies, response models, and DTOs reside in:
   `org.opencode.mobile.networking.client.generated.*` inside `shared/networking`.
2. The domain defines pure Kotlin domain models (`Session`, `Message`, `PermissionRequest`, `QuestionRequest`, `HealthStatus`) and the `OpenCodeGateway` interface in `shared/domain`.
3. `OpenCodeV2Adapter` (in `shared/networking`) is the **sole consumer** of the generated client. It translates generated DTOs into domain entities and exposes only domain types or flows to `shared/data` and `shared/application`.
4. Automated verification via `scripts/verify-r3-generated-types.sh` runs in CI to ensure that no Kotlin file outside `shared/networking` imports `org.opencode.mobile.networking.client.generated.*`.

---

## 5. Consequences & Follow-Up

- **Zero Breaking Mismatches**: All capabilities assumed in §6.1 exist in the pinned OpenAPI spec. No spec revisions or backward-incompatible concessions are required.
- **Client Generation Stable**: The generated client compiles cleanly using Ktor 3.0.1, Kotlin 2.1.0, and `kotlinx.serialization`.
- **Implementation Path**:
  - `OpenCodeV2Adapter` can now be implemented against concrete, typed methods in `OpenCodeApiClient`.
  - `MockOpenCodeServer` (OPE-32) can use the same spec fixtures to guarantee behavioral equivalence.
