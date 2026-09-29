# ADR 0006: Markdown Rendering and Syntax Highlighting for Compose Multiplatform (Q25)

- **Status**: Accepted
- **Date**: 2026-09-29
- **Author**: Engineer
- **Related issues**: OPE-105 (this ADR), OPE-109 (L3 transcript implementation), OPE-103 (L0 gate)
- **References**:
  - Cahier des charges d'architecture v1.0, §10: « Librairies de rendu Markdown et coloration syntaxique : à évaluer par ADR sur critères **CMP (Android + iOS), performance, licence** (Q25). »
  - ADR 0003: Liquid Glass vs. pure Compose Multiplatform on iOS (OP1)
  - ADR 0004: Architecture/Dependency Rule Enforcement via Konsist (§5.2)
  - `mikepenz/multiplatform-markdown-renderer` — https://github.com/mikepenz/multiplatform-markdown-renderer (Apache-2.0)
  - `JetBrains/markdown` (intellij-markdown) — https://github.com/JetBrains/markdown (Apache-2.0)
  - `SnipMeDev/Highlights` — https://github.com/SnipMeDev/Highlights (Apache-2.0)

> **Numbering note.** `0005` is claimed by the in-flight ADR "V1 open decisions OP1–OP5" (OPE-113 / PR #21), which is not yet merged into `main` at the time of writing. To avoid two different ADRs sharing the number `0005`, this catch-up ADR (OPE-105) takes the next free number, `0006`. Do not renumber this file to `0005` after PR #21 lands — that would create an index collision.

## Context

OpenCode Mobile renders assistant output in the **streaming transcript** (Lot L3, requirement V1-05), and reuses Markdown/code rendering in the **file and diff viewers** (Lot L5, V1-11/V1-12). OpenCode agents answer in Markdown: headings, lists, tables, task lists, links, inline code, blockquotes, and — most importantly — **fenced code blocks**, usually tagged with a language (Kotlin, TypeScript, shell, JSON, …).

Three constraints follow directly from the specification (§10):

1. **CMP (Android + iOS from `commonMain`).** The UI is shared Compose Multiplatform (ADR 0003). An Android-only renderer would force a second, native iOS implementation, contradicting the "shared UI" mandate in §4 D4.
2. **Performance.** The transcript is *streaming*: assistant text arrives incrementally over SSE (L2 `EventProcessor`). Re-parsing and re-rendering the whole message on every chunk is the worst case for the §10.1 targets (smooth scrolling, no jank on long transcripts). Parsing must happen off the composition and should be incremental.
3. **License.** The app is distributed on Google Play and the Apple App Store (L6). Everything compiled into the shipped binary must be under a permissive license; copyleft (GPL/AGPL/LGPL) and non-commercial/source-available terms are unacceptable.

One architectural constraint applies (ADR 0004, §5.2):

- Rule 9 — `:design-system` may depend only on Compose and the Kotlin stdlib, so the renderer must **not** be declared there.
- Rule 8 — a feature may depend on `shared/domain`, `shared/application`, `design-system`, Compose and Koin. Markdown rendering is a UI dependency and belongs to `:features:transcript`.

This ADR covers the **Markdown→Compose rendering library** and the **syntax-highlighting engine**. It does not cover the composer's input parsing, the L5 diff viewer, or the policy for remote images (see "Security and privacy notes").

## Options considered

### Markdown rendering

| Option | Description |
|---|---|
| **A** | `com.mikepenz:multiplatform-markdown-renderer` (+ `-m3`) |
| **B** | `org.jetbrains:markdown` (intellij-markdown) parser + an in-house Compose renderer |
| **C** | Android-only Compose Markdown libraries (e.g. `com.halilibo.compose-richtext`, `com.github.jeziellago:compose-markdown`) |
| **D** | Full in-house Markdown parser + renderer |

**Option A — `com.mikepenz:multiplatform-markdown-renderer`.** A Kotlin Multiplatform / Compose Multiplatform Markdown renderer by Mike Penz. It parses with `org.jetbrains:markdown` (intellij-markdown) and renders native Compose. It supports Android, iOS, Desktop (JVM), Web (Wasm/JS) and macOS from `commonMain`. Since 0.13.0 the core carries no theme; theming comes from the `-m2` or `-m3` module. It implements full GFM (tables, task lists, strikethrough, autolinks, GitHub alerts), overrides every AST node through `MarkdownComponents`, and offers first-class **streaming** (`rememberStreamingMarkdownState()` / `collectAsStreamingMarkdownState()` append chunks and re-parse only the *unstable tail*). Syntax highlighting is an optional `-code` module (introduced in 0.27.0). License: Apache-2.0 (forked portions MIT).

**Option B — `org.jetbrains:markdown` + in-house renderer.** Use the JetBrains multiplatform Markdown *parser* (JVM/JS/Native) and write our own AST→Compose renderer (`AnnotatedString`/`Text`, custom code-fence composable). Parser license Apache-2.0, official JetBrains project (~959★, actively maintained). Maximum control and no UI-library dependency, but we then own block/inline rendering, spacing, links, tables, code fences, theming, streaming performance and every future bug.

**Option C — Android-only libraries.** Mature and convenient on Android, but not Compose Multiplatform (no iOS target). Rejected: it forces a separate iOS renderer and breaks ADR 0003 / §4 D4.

**Option D — Full in-house parser + renderer.** Maximum control and zero third-party. Rejected: implementing CommonMark/GFM correctly, and keeping it correct against adversarial input, is a large permanent maintenance cost with no product differentiation.

### Syntax highlighting

| Option | Description |
|---|---|
| **1** | Highlights (`dev.snipme:highlights`), reached through the renderer's `-code` module |
| **2** | In-house Kotlin tokenizer |
| **3** | Wrap a WebView + highlight.js, or a per-platform native highlighter |

**Option 1 — Highlights.** Pure-Kotlin KMP syntax-highlighting engine (Apache-2.0). Bundled languages include Kotlin, Java, Swift, C/C++, JavaScript, TypeScript, Python, Rust, Go, Shell, PHP, Ruby, C#, Dart. It ships themes (Darcula, Monokai, Atom One, Notepad, Matrix, Pastel, and light variants), result caching and incremental re-analysis. The renderer's `-code` module wires it into the `codeBlock`/`codeFence` components and renders a language header plus a copy button.

**Option 2 — In-house tokenizer.** Full control of language coverage, but another permanent maintenance burden and a source of rendering bugs. Rejected for V1.

**Option 3 — WebView / native.** A WebView reintroduces a JS runtime (weight, consistency, CVE surface) and breaks the shared-UI model; a per-platform native highlighter duplicates work and cannot live in `commonMain`. Rejected.

## Comparison

| Criterion | A: mikepenz renderer (+ `-code`) | B: intellij-markdown + in-house renderer | C: Android-only libs | D: full in-house |
|---|---|---|---|---|
| CMP Android + iOS from `commonMain` | Yes (Android, iOS, JVM, Wasm/JS, macOS) | Parser yes; renderer must be written | **No** (iOS missing) | Yes, once written |
| Performance / streaming | Async parse off-composition; append-only streaming re-parses only the unstable tail | Fully controllable, but must be built and tuned | n/a on iOS | Risk equals B plus parser risk |
| License | Apache-2.0 (fork portions MIT) — permissive | Apache-2.0 — permissive | Apache-2.0/MIT but blocked on iOS | n/a |
| GFM coverage | Full (tables, task lists, alerts, autolinks) | GFM parser available | Partial | Must implement |
| Theming with the design system | `markdownColor()` / `markdownTypography()`; M3 defaults | Full control | Android theme only | Full control |
| Effort to ship L3 | Low (integration only) | Medium–High | Low on Android, High on iOS | Very High |
| Maintenance / supply chain | Active (v0.45.0, Aug 2026; ~1.1k★); one maintainer | JetBrains official (~959★) | Android-only | Us |

## Decision

**Adopt Option A for rendering and Option 1 for highlighting.**

1. **Markdown rendering**: `com.mikepenz:multiplatform-markdown-renderer` + `com.mikepenz:multiplatform-markdown-renderer-m3`, version **0.45.0** (latest release at 2026-09-29). The Material 3 module is chosen because the design system targets Material 3.
2. **Syntax highlighting**: `com.mikepenz:multiplatform-markdown-renderer-code` (same version), which uses **`dev.snipme:highlights` 1.1.0**. Use `highlightedCodeBlock` / `highlightedCodeFence` with `showHeader = true` (language label + copy button) and a theme bound to the app light/dark theme (`SyntaxThemes.atom(darkMode = isDarkSystemTheme())` or equivalent).
3. **Dependency placement**: declare both modules in `:features:transcript` `commonMain` only, **never** in `:design-system` (ADR 0004, §5.2 rule 9). Register the versions in `gradle/libs.versions.toml`.
4. **Streaming**: render assistant messages through `rememberStreamingMarkdownState()` / `collectAsStreamingMarkdownState()` fed by the streaming chunks, so only the unstable tail is re-parsed as tokens arrive.
5. **State retention**: use `retainState = true` on content updates to avoid a loading flicker when a message is re-rendered.

**Rationale.** Option A is the only evaluated option that satisfies all three §10 criteria at once — CMP Android+iOS, streaming performance, permissive license — at integration-only cost. It uses the JetBrains GFM parser internally, so we still get a standards-compliant parser without owning one. Option B stays viable as a fallback: it uses the *same* parser, so if the renderer is ever abandoned we can replace it with an in-house AST→Compose renderer. To keep that fallback cheap, the renderer is wrapped behind a single composable in `:features:transcript` so the swap stays local.

## License compatibility (Play Store / App Store)

| Component | Version | License | Distribution impact |
|---|---|---|---|
| `com.mikepenz:multiplatform-markdown-renderer` (+ `-m3`, `-code`) | 0.45.0 | Apache-2.0 (forked portions MIT) | Permissive; OK for Play and App Store |
| `org.jetbrains:markdown` (transitive) | current | Apache-2.0 | Permissive; OK |
| `dev.snipme:highlights` | 1.1.0 | Apache-2.0 | Permissive; OK |
| extended-spans (Saket Narayan; bundled by the renderer) | — | Apache-2.0 | Permissive; OK |
| Compose Multiplatform (already in use) | 1.7.1 | Apache-2.0 | OK |

**Obligations.** Apache-2.0 requires preserving copyright and license notices and including the license text in the app's open-source-licenses screen (L6). There is no copyleft, so static linking into the iOS framework is permitted.

**Decision rule going forward.** Only permissive licenses (Apache-2.0 / MIT / BSD) may enter the shipped binary. GPL/AGPL/LGPL and non-commercial or source-available terms are rejected for any library added to the app.

## Consequences

### Positive

- Standards-compliant **GFM** rendering with no parser to maintain ourselves.
- **Streaming-friendly** parsing that matches the SSE transcript model.
- **Material 3 theming** hooks (`markdownColor`, `markdownTypography`) keep the transcript consistent with the design system.
- Per-node overrides let us later customise code fences, link handling, or checkboxes without forking the library.
- Permissive, App-Store-safe license chain.

### Negative / risks

- **Third-party, single-maintainer renderer.** Version drift against our Kotlin 2.1.0 / Compose Multiplatform 1.7.1 / AGP 8.7.2 stack is possible. Mitigation: pin versions in `gradle/libs.versions.toml`, wrap the API behind our own composable, and keep the CI build compiling `:features:transcript` for Android and the iOS targets.
- **Language coverage of Highlights is finite.** There are no dedicated JSON/XML/YAML lexers; those fences fall back to plain monospace. Acceptable for V1 — unknown languages always fall back to plain code, and a custom `codeFence` component can be added later (new ADR if the work is substantial).
- **Binary size.** The parser, Highlights and extended-spans add to the iOS static framework. Measure in L6.
- **Performance is not proven yet.** The decision is based on the library's design (async parse, incremental streaming, lazy rendering); it must be validated against `MockOpenCodeServer` streaming and long transcripts in L3.

## Security and privacy notes

- **Untrusted input.** Markdown arriving from the server is untrusted. `multiplatform-markdown-renderer` renders to Compose UI, not to a WebView, so there is no HTML/script execution path. Keep it that way: do not add a WebView fallback.
- **Links.** Open only `http`/`https` in the platform browser; do not auto-open arbitrary custom schemes (L3 follow-up).
- **Remote images.** A transcript can embed `![...](https://third-party/...)`. Loading those would make the device contact arbitrary third-party hosts, which conflicts with the no-telemetry / no-unexpected-network promise. For V1, **do not** wire an image transformer (`coil2`/`coil3`) into the transcript renderer; render image nodes as a link or placeholder. A dedicated decision can revisit image loading.
- **Highlights is offline.** It is pure Kotlin, with no network and no telemetry.

## Verification

- A rendering test in `:features:transcript` covering a representative GFM sample — headings, lists, a table, a task list, inline code, a Kotlin fence and a JSON fence — asserting the expected composable output (screenshot or semantics test as available).
- CI must compile `:features:transcript` for `androidTarget` and the iOS targets (the existing build step) to catch Kotlin/Native issues early.
- A manual/benchmark pass against `MockOpenCodeServer` streaming a long message: no jank, stable frame timing.

## Follow-up / implementation

- **Implementation issue: [OPE-109](/OPE/issues/OPE-109) — [MVP L3] Chat V1-05 : envoi de prompt, transcript en streaming, Markdown + code coloré.** This ADR fixes the library choice only; the actual wiring (wrapper composable, theming, streaming state, code-fence components) lands there.
- Add the `markdown-renderer` versions to `gradle/libs.versions.toml` and the `:features:transcript` `commonMain` dependencies in OPE-109.
- L5 (V1-11/V1-12) reuses the same renderer for file/diff display; no new ADR unless the highlighting requirements change.
- L6: add the third-party license texts to the in-app open-source-licenses screen and re-check license compatibility before store submission.

## References

- Cahier des charges d'architecture v1.0, §10 (Q25) and §4 D4 — attached to OPE-2.
- ADR 0003: Liquid Glass vs. pure Compose Multiplatform on iOS.
- ADR 0004: Architecture/dependency rule enforcement via Konsist.
- https://github.com/mikepenz/multiplatform-markdown-renderer (Apache-2.0; v0.45.0)
- https://github.com/JetBrains/markdown (Apache-2.0)
- https://github.com/SnipMeDev/Highlights (Apache-2.0; 1.1.0)
