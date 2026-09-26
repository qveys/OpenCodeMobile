# ADR 0004: Architecture/Dependency Rule Enforcement via Konsist

- **Status**: Accepted
- **Date**: 2026-09-24
- **Author**: Engineer
- **Related Issue**: OPE-33 (Add CI architecture/dependency-rule tests for module boundaries)
- **References**:
  - Cahier des charges d'architecture v1.0 §5.2 (attached to OPE-2)
  - ADR-0001: Monorepo module scaffold
  - Konsist: https://github.com/konsist/konsist

## Context

The architecture specification (*Cahier des charges d'architecture v1.0*, §5.2) defines strict module boundary rules that must be enforced to maintain Clean Architecture layering in the Kotlin Multiplatform monorepo. Without automated enforcement, these rules rely solely on code review discipline, which is insufficient for a solo maintainer project where layering drift can silently accumulate.

The rules from §5.2 are:

1. **Domain layer isolation**: `shared/domain` must depend only on Kotlin stdlib — no data, networking, persistence, Ktor, Compose, SQLDelight, Koin, or generated OpenAPI client.
2. **Application layer**: `shared/application` may only depend on `shared/domain` and `kotlinx.coroutines`.
3. **Data layer**: `shared/data` may depend on `shared/domain`, `shared/networking`, `shared/realtime`, `shared/persistence`, `shared/security`.
4. **Networking layer**: `shared/networking` may only depend on `shared/domain` and Ktor. It exclusively owns the generated OpenAPI client.
5. **Realtime layer**: `shared/realtime` may only depend on `shared/domain` and `shared/networking`.
6. **Persistence layer**: `shared/persistence` may only depend on `shared/domain` and SQLDelight.
7. **Security layer**: `shared/security` may only depend on `shared/domain`.
8. **Feature isolation**: Each `features/*` module may only depend on `shared/domain`, `shared/application`, `design-system`, Compose, and Koin. Features must not reach into another feature's internals.
9. **Design system**: `design-system` may only depend on Compose and Kotlin stdlib.
10. **UI/ViewModel restrictions**: Features, design-system, and androidApp must not directly import Ktor (HTTP/SSE), SQLDelight, or SecureStore/Keystore APIs.
11. **Generated code protection**: The generated OpenAPI client in `shared/networking/src/commonMain/kotlin/org/opencode/mobile/networking/client/generated/` must never be hand-edited.
12. **Koin modules**: Koin module declarations are only permitted in `androidApp` (composition root) and `features/*` modules.

## Decision

We adopt **Konsist** as the architecture testing framework, running as a dedicated JVM test module (`architecture-tests/`) in CI. Konsist provides a fluent Kotlin DSL for asserting module dependencies, import restrictions, and custom rules — all verified at test time.

### Implementation

1. **New module**: `architecture-tests/` — a JVM-only Kotlin module containing Konsist tests.
2. **Test suite**: `ModuleBoundaryTest.kt` — encodes all §5.2 rules as executable assertions.
3. **CI integration**: GitHub Actions workflow (`.github/workflows/architecture-tests.yml`) runs `:architecture-tests:test` on every PR and push to `main`.
4. **Fail-fast**: Any forbidden dependency, import, or pattern causes the build to fail, blocking merge.

### Konsist Scope Configuration

The test scope covers all Kotlin source directories under:
- `shared/*/src/commonMain/kotlin`
- `features/*/src/commonMain/kotlin`
- `design-system/src/commonMain/kotlin`
- `androidApp/src/main/kotlin`

This ensures the rules apply to the actual source code that ships, not just Gradle dependency declarations.

## Consequences

### Positive

- **Automated guardrails**: Layering violations are caught in CI before they reach `main`.
- **Living documentation**: The test file itself serves as executable documentation of §5.2 rules.
- **Refactoring safety**: Moving code between modules or adding new dependencies is validated immediately.
- **Zero runtime cost**: Konsist runs as a static analysis test; no production dependencies added.

### Negative / Trade-offs

- **JVM-only test module**: `architecture-tests/` runs on JVM only (Konsist requirement). This is acceptable because it analyzes source code statically; it does not need to compile for iOS/Android targets.
- **Konsist version pinned**: Added `konsist = "0.17.3"` to `gradle/libs.versions.toml` with `konsist` and `konsist-test` libraries.
- **Test execution time**: Adds ~30-60s to CI. Acceptable for PR gate.

### Known Gaps / Follow-up

- **Generated client mutation detection**: The current test checks for "DO NOT EDIT" markers but a more robust check (e.g., git diff vs. generator output) could be added later.
- **iOSApp not analyzed**: `iosApp/` is a native Xcode project (Swift), not Kotlin. §5.2 rules apply only to Kotlin modules; Swift layering is a separate concern.
- **Compose Multiplatform internal imports**: The test forbids `org.jetbrains.compose.**` and `androidx.compose.**` in non-UI layers. If a shared utility needs a Compose type (e.g., `Dp`), an exception would need an ADR.

## Verification

Run locally:
```bash
./gradlew :architecture-tests:test
```

In CI: The `architecture-tests` workflow runs on every PR and push to `main`.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Gradle `dependencyVerification` / `strictDependencies` | Only validates declared Gradle dependencies, not actual source-level imports (e.g., a feature importing `ktor-client-core` classes without declaring the dependency). |
| `archunit` (JVM) | Java-centric, doesn't understand Kotlin Multiplatform source sets or Compose. |
| Custom Gradle plugin / `detekt` rules | Higher maintenance burden; Konsist is purpose-built for Kotlin architecture testing with a fluent DSL. |
| Manual code review only | Insufficient for solo maintainer; drift is inevitable without automation. |

## References

- Konsist documentation: https://konsist.dev/
- Konsist layer assertions: https://konsist.dev/docs/layers/
- Cahier des charges v1.0 §5.2 (source of truth for rules)