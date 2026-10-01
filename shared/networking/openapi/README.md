# OpenCode Server v2 OpenAPI Specification

This directory vendors the pinned OpenAPI specification for **OpenCode Server v2**.

## Specification Metadata

- **Target Server Version**: `2.0.6`
- **OpenAPI Version**: `3.1.0`
- **Spec Title**: `opencode` (version `1.0.0`)
- **Extracted From**: `1.18.32`, on 2026-09-24, via `GET /doc` on a live `opencode serve` instance
- **Source Endpoint**: `GET /doc` on live `opencode serve` instance
- **File**: [`opencode-server-v2.json`](opencode-server-v2.json)
- **SHA-256 Checksum**: `46db986090aae41846cd6dbe16225a1d883f0bbcb4c48814008d3f6ce140aa5c`

> [!IMPORTANT]
> The supported server version is `2.0.6`, but the vendored spec was extracted
> from `1.18.32`. The checksum above covers only those bytes. Re-vendor against a
> `2.0.6` instance and update the checksum when re-vendoring.

## Architecture Rules & Boundary Contracts

1. **Rule R3 (Absolute)**: Generated types produced from this specification live exclusively inside `shared/networking` and **must never be exposed or imported outside `shared/networking`**. All communication between the rest of the application and the generated API client must pass through the `OpenCodeV2Adapter` implementing the `OpenCodeGateway` domain port.
2. **Rule R12 (Absolute)**: Generated client code is **never manually edited**. Any updates must come from regenerating against this pinned specification.
3. **Rule R13 (Absolute)**: Every protocol assumption and version idiosyncrasy is isolated behind `OpenCodeV2Adapter`.
4. **ADR Mapping**: See [`docs/adr/ADR-0002-opencode-v2-api-surface-mapping.md`](../../../docs/adr/ADR-0002-opencode-v2-api-surface-mapping.md) for the exhaustive mapping between Cahier des charges v1.0 §6.1 assumed endpoints and this specification.

## Code Generation

Code generation is wired into the Gradle build:

```bash
# Via Gradle task
./gradlew :shared:networking:generateOpenApiClient

# Or via generator script
./scripts/generate-openapi-client.sh
```

## Updating / Re-vendoring Procedure

1. Run the target OpenCode Server instance:
   ```bash
   opencode serve --port 45678
   ```
2. Verify version:
   ```bash
   curl -s http://127.0.0.1:45678/global/health
   ```
3. Fetch new specification:
   ```bash
   curl -s http://127.0.0.1:45678/doc > shared/networking/openapi/opencode-server-v2.json
   ```
4. Update SHA-256 and metadata in this README.
5. Re-run client generation:
   ```bash
   ./scripts/generate-openapi-client.sh
   ```
6. Update `OpenCodeV2Adapter` to accommodate any contract additions or breaking changes.
7. Verify architecture boundary rules:
   ```bash
   ./scripts/verify-r3-generated-types.sh
   ```
8. Record any endpoint differences in an Architectural Decision Record (ADR).
