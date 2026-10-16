# OpenCode Server v2 OpenAPI Specification

This directory vendors the pinned OpenAPI specification for **OpenCode Server v2**.

## Specification Metadata

- **Target Server Version**: `2.0.6`
- **OpenAPI Version**: `3.1.0`
- **Spec Title**: `opencode HttpApi` (version `0.0.1`)
- **Extracted From**: upstream `sst/opencode` tag `v2.0.6`, file `packages/protocol/openapi.json` (112 paths, all under `/api`), on 2026-10-16
- **Source**: upstream repository file (the 2.0.6 server serves no `/doc`; the root surface is gone)
- **File**: [`opencode-server-v2.json`](opencode-server-v2.json)
- **SHA-256 Checksum**: `fd96c818f4d26e822664d4401c7fc115973c41db8dbd79aac1a3ea1840a64cd8`

> [!NOTE]
> The generator (`scripts/generate-openapi-client.py`) emits a hand-maintained
> subset of routes and fails if one is missing from this spec.

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

1. Fetch the contract for the target tag:
   ```bash
   gh api -H 'Accept: application/vnd.github.raw' 'repos/sst/opencode/contents/packages/protocol/openapi.json?ref=v<version>' > shared/networking/openapi/opencode-server-v2.json
   ```
2. Update SHA-256 and metadata in this README.
3. Re-run client generation:
   ```bash
   ./scripts/generate-openapi-client.sh
   ```
4. Update `OpenCodeV2Adapter` to accommodate any contract additions or breaking changes.
5. Verify architecture boundary rules:
   ```bash
   ./scripts/verify-r3-generated-types.sh
   ```
6. Record any endpoint differences in an Architectural Decision Record (ADR).
