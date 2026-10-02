# ADR 0007: Markdown renderer version pinned by the toolchain (amends ADR 0006)

- **Status**: Accepted
- **Date**: 2026-09-29
- **Author**: Engineer
- **Related issues**: OPE-109 (V1-05 transcript), OPE-105 / ADR 0006 (renderer choice), OPE-137 (architecture gate)
- **Amends**: the **version pin only** of ADR 0006. The library choice
  (`com.mikepenz:multiplatform-markdown-renderer` + `-m3` + `-code`/Highlights,
  Apache-2.0, declared in `:features:transcript` only) is unchanged.

## Context

ADR 0006 selected `com.mikepenz:multiplatform-markdown-renderer` **0.45.0** for the
V1-05 transcript. Implementing V1-05 (OPE-109) showed that release cannot be built
with the toolchain this repository pins (ADR 0001: Kotlin **2.1.0**, Compose
Multiplatform **1.7.1**):

1. **Kotlin metadata.** `0.45.0` (and every release from `0.43.0` on) is compiled
   with Kotlin 2.4 metadata. Kotlin 2.1.0 refuses it:
   `Module was compiled with an incompatible version of Kotlin ... metadata is 2.4.0, expected 2.1.0`.
2. **Compose `ui-backhandler`.** `0.35.0` is readable by Kotlin 2.1.0, but its iOS
   klib manifest declares `org.jetbrains.compose.ui:ui-backhandler`, an artifact
   introduced in Compose Multiplatform **1.8.0** that this repository's CMP 1.7.1
   does not ship. The iOS framework link then fails:
   `KLIB resolver: Could not find "org.jetbrains.compose.ui:ui-backhandler"`.

Both constraints are hard: the repository cannot move to Kotlin 2.4 / CMP 1.8 in
L3, and the ADR 0006 API surface (`markdownComponents(codeFence = ...)`,
`highlightedCodeFence`) is needed for the V1-05 code rendering.

## Decision

Pin `multiplatform-markdown-renderer` to **0.33.0** in `gradle/libs.versions.toml`.

`0.33.0` is the **newest** release that is compatible with **both** constraints:

| Release | Kotlin metadata | iOS klib needs `ui-backhandler` | Usable |
|---|---|---|---|
| 0.45.0 (ADR 0006) | 2.4.0 | yes | no |
| 0.35.0 | 2.1.21 | yes | no |
| 0.34.0 | 2.1.20 | yes | no |
| **0.33.0** | **2.1.20** | **no** | **yes** |
| 0.30.0 | 2.1.0 | no | yes (older) |

Verification: the `core`/`-m3`/`-code` iOS klib manifests for `0.33.0` were
inspected for `ui-backhandler` (absent) and report ABI `1.201.0`; the Android
targets compile and the iOS framework link is green in CI.

## Consequences

- **Code rendering works**: `highlightedCodeFence` (Highlights `-code`) is present
  in `0.33.0`, with the same `markdownComponents(codeFence = ...)` wiring.
- **No built-in code header / copy button.** Those were added in a later release
  that is blocked by the two constraints above. For V1-05 the assistant message is
  wrapped in a `SelectionContainer`, so code is selectable and copyable with the
  platform selection menu. An explicit per-fence copy button is a follow-up gated
  on a Kotlin + CMP upgrade (track with the toolchain upgrade, not V1-05).
- The renderer stays behind the single `AssistantMarkdown` wrapper
  (`features/transcript`), so a later version bump or library swap stays local.

## When this ADR is revisited

Raise the pin back toward ADR 0006's 0.45.0 once the repository moves to Kotlin
2.4+ and Compose Multiplatform 1.8+, then restore `showHeader = true` for the
language header and copy button. Re-run `:features:transcript` for Android and the
iOS framework link in CI.

## References

- ADR 0006: Markdown rendering and syntax highlighting (library choice).
- ADR 0001: Monorepo module scaffold (toolchain pins: Kotlin 2.1.0, CMP 1.7.1).
- OPE-109, PR #35 (implementation and CI evidence).
