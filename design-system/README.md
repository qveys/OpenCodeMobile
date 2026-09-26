# design-system — imported sources

This directory holds the byte-for-byte design-system sources supplied for
OpenCode Mobile on Paperclip OPE-21 and the official OpenCode marks. It is a source
archive, not the component implementation. The human-readable specification
lives in [`docs/DESIGN-SYSTEM.md`](../docs/DESIGN-SYSTEM.md).

## Layout

| Path | Contents |
|---|---|
| `source/tokens.json` | Token set, v1 (colour themes, type, spacing, radius, shadow, size). Imported byte-for-byte. |
| `source/design-system.json` | Original supplier manifest, v3. Imported byte-for-byte and preserved unmodified. |
| `logos/*.svg` | The eight official OpenCode marks (logos and wordmarks, light/dark). |
| `references/*_preview.html` | The 13 component previews plus `Cover_preview.html` (14 reference files in total). Reference only; not runtime code. |
| `SOURCES.sha256` | Local SHA-256 of every file in `source/`, `logos/` and `references/`. |

## Provenance and integrity

- Every file was downloaded from its Paperclip attachment on OPE-21 and
  verified against the attachment's declared `byteSize` and `sha256` before
  import.
- `SOURCES.sha256` records the local hashes computed during import. Verify
  at any time with:

  ```sh
  scripts/verify-design-system-sources.sh
  ```

- The eight SVGs are the **source of record** for the logo assets. They are
  imported byte-for-byte; nothing was redrawn, recoloured or re-serialised.

## Manifest size discrepancy (recorded, not resolved)

`source/design-system.json` declares a size for each logo that is larger
than the real attachment. The delta is constant per variant (logo −24,
wordmark-simple −60, wordmark −108 bytes), so it is not a line-ending or BOM
artefact. The attachments are treated as authoritative; the manifest is
preserved unchanged for traceability.

| File | Manifest size | Actual bytes | Δ | Local SHA-256 |
|---|---:|---:|---:|---|
| `opencode-logo-light.svg` | 601 | 577 | −24 | `5eeb3a5f…953380` |
| `opencode-logo-dark.svg` | 601 | 577 | −24 | `4e84f87a…0d7ed0` |
| `opencode-logo-light-square.svg` | 637 | 613 | −24 | `4510b522…f6285e` |
| `opencode-logo-dark-square.svg` | 637 | 613 | −24 | `c3ba0e84…c52404` |
| `opencode-wordmark-light.svg` | 2226 | 2118 | −108 | `63577a14…1b525f` |
| `opencode-wordmark-dark.svg` | 2244 | 2136 | −108 | `2a85aa81…c5e6ab` |
| `opencode-wordmark-simple-light.svg` | 1554 | 1494 | −60 | `c0c19dd9…4ef06a` |
| `opencode-wordmark-simple-dark.svg` | 1577 | 1517 | −60 | `e48e97a8…d87264` |

The manifest covers only the `Logos` group; `tokens.json`, the 13 component
previews and `Cover_preview.html` have no entry in it.

## References are not runnable

The `references/*.html` previews are structural fragments. They reference
classes (`oc`, `oc-btn`, `oc-status`, …) and CSS variables with no attached
stylesheet, so they do not render the design system standalone. Treat them
as annotated markup, not as a visual baseline.

`Cover_preview.html` is a store/cover reference asset. It is outside the
reusable runtime component scope (CEO decision in the OPE-21 handoff review) and
is not an L0/L3 implementation requirement.

## Fonts

`tokens.json` declares the `mono` family
(`Berkeley Mono → IBM Plex Mono → ui-monospace → SF Mono/Menlo`) but ships
**no font files** (`type.fonts` is empty). IBM Plex Mono must be obtained
under the SIL Open Font License and bundled with its licence; Berkeley Mono
is commercial and must only be used if separately licensed. No font binary
is committed here.
