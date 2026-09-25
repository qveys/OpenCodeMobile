# OpenCode Mobile — Design System (V1)

Status: **V1 specification, approved for implementation.** This document is the
in-repo source of truth for the visual language of OpenCode Mobile. It
consolidates the supplier design system received on Paperclip OPE-21, the
accepted integration plan (OPE-21 `plan`), and the binding CEO review of the
handoff (`OPE-21` `ceo-handoff-review`).

Scope of this document: colour, typography, spacing, radii, depth, metrics,
component patterns and brand rules. It does **not** define navigation or screen
flows (OPE-22), and it contains no networking, persistence or OpenAPI types.

Imported sources and their integrity records live in
[`design-system/`](../design-system/README.md).

## 1. What OpenCode Mobile is

A companion client for an OpenCode server: start, monitor, steer, approve and
review coding-agent sessions from a phone. It is not a mobile IDE and not a
consumer chatbot. It must read as OpenCode before any logo is visible: a man
page in your pocket — cream and ink chrome, a terminal-black session, gold only
when the agent is live, monospace everywhere.

Design principles:

- **Content is the UI.** Transcript, tool calls, diffs, files and agent status
  carry the screen; chrome stays minimal.
- **Terminal-native, not chat-native.** A session is an execution log. No
  bubbles, no avatars, no left/right alignment.
- **Dense, not cramped.** Pack information; keep targets ≥ 44 dp (iOS) / 48 dp
  (Android). The visible glyph may be smaller than its hit area.
- **Quiet by default.** About 90 % neutral. Colour always means something:
  agent activity, status, diff, syntax.
- **ASCII before decoration.** `[+] [-] [!] [x] > › ⌄ ● ○ ✓ + − ···` before any
  icon.
- **Flat.** Structure comes from spacing, 1 px hairlines and surface shifts —
  never shadows.
- **Progressive disclosure.** Summary → expand inline → full-screen inspector,
  for tools, files, logs, reasoning and metadata.
- **Native behaviour, OpenCode surfaces.** Stacks, sheets, swipe/predictive
  back, Dynamic Type — without Material You or iMessage visuals.
- **Motion is state.** Only execution, navigation and expansion move.

## 2. Binding decisions

These decisions resolve the open questions raised in the handoff and supersede
any contrary wording in the supplier README or previews.

1. **CMP pure throughout V1 — no Liquid Glass.** There is no native material
   exception for tab bars, toolbars or sheet chrome. Every sheet is an opaque
   CMP surface. This is also the design-system input for OPE-34. The supplier
   README's Liquid Glass allowance is superseded.
2. **Session is always dark, regardless of system appearance.** `chrome` /
   `chrome-dark` follow the system; `session` never lightens.
3. **SVG source of record is the actual attachment set.** The eight marks are
   imported byte-for-byte; local SHA-256 hashes and the manifest size
   discrepancies are recorded (see [`design-system/README.md`](../design-system/README.md)).
   The manifest is preserved unchanged. Blob IDs are not URLs, and no identity
   with the current website is claimed.
4. **Contrast fallbacks are implementation requirements** (§5).
5. **Cover is a source/reference asset**, outside the reusable runtime
   component scope and not an L0/L3 requirement.
6. **Actions reflect verified server capabilities only.** No permission
   decision, destructive action or capability is hard-coded in a component.
7. **Lot split L0 / L3** as fixed in §10.

## 3. Three themes, one token set

| theme | where | ground | ink | primary |
|---|---|---|---|---|
| `chrome` | onboarding, connect, projects, sessions list, settings, inbox — system light mode | `bg` `#fdfcfc` | `text` `#201d1d` | ink `#201d1d` |
| `chrome-dark` | the same screens in system dark mode | `bg` `#131010` | `text` `#f2eded` | `#f2eded` |
| `session` | an open session: transcript, tools, diffs, composer, permission banner — always dark | `bg` `#0a0a0a` | `text` `#eeeeee` | gold `#fab283` |

- Components consume **semantic tokens only** (`bg`, `text`, `primary`,
  `agent`, …). The context resolves them. `data-theme="session"` (or the CMP
  equivalent) is set on the session root.
- Do not alternate contexts inside a screen. Navigating into a session is the
  one chrome → session transition.
- `brand-*` tokens are constants for the wordmark, splash and cover — never for
  ordinary UI.

## 4. Colour tokens

All per-theme colour tokens from `design-system/source/tokens.json` (v1):

| token | `chrome` | `chrome-dark` | `session` | usage |
|---|---|---|---|---|
| `bg` | `#fdfcfc` | `#131010` | `#0a0a0a` | Page ground. Always opaque. |
| `bg-panel` | `#f8f7f7` | `#1b1818` | `#141414` | Inputs, pressed/selected rows, code, composer field. |
| `bg-raised` | `#f1eeee` | `#292424` | `#1c1c1c` | Secondary controls, permission banner, sheet content, disabled fills. |
| `line` | `rgba(15,0,0,.12)` | `#3d3838` | `#282828` | Default 1 px hairline; ghost-button border. |
| `line-strong` | `#646262` | `#7f7a7a` | `#707070` | Input and keycap borders (≥ 3:1 on every ground). |
| `text` | `#201d1d` | `#f2eded` | `#eeeeee` | Primary text and headings; ≥ 13:1 on every ground. |
| `text-body` | `#646262` | `#b8b2b2` | `#a0a0a0` | Body copy and long secondary text. |
| `text-muted` | `#646262` | `#9a9898` | `#808080` | Metadata: timestamps, paths, model ids, line numbers. |
| `text-weak` | `#9a9898` | `#7f7a7a` | `#707070` | Placeholders and disabled labels only — below 4.5:1 by design. |
| `primary` | `#201d1d` | `#f2eded` | `#fab283` | Primary action fill, one per view. |
| `primary-pressed` | `#302c2c` | `#f8f6f6` | `#ffa478` | Pressed primary. |
| `on-primary` | `#fdfcfc` | `#131010` | `#0a0a0a` | Label on `primary`. |
| `agent` | `#954c27` | `#fab283` | `#fab283` | Live agent activity as text or glyph (`●`, caret). |
| `interactive` | `#3b5cf6` | `#a2bcff` | `#a2bcff` | Links and inline references only — never a CTA fill. |
| `focus` | `#3b5cf6` | `#a2bcff` | `#fab283` | 2 px focus ring, offset 2 px. |
| `success` | `#1d783c` | `#12c905` | `#12c905` | Completed, passing, valid. Always with `✓`/`[+]` and a word. |
| `warning` | `#68552b` | `#fcd53a` | `#fcd53a` | Attention, permissions, consequential actions. Always with `[!]`. |
| `danger` | `#b82d35` | `#fc533a` | `#fc533a` | Failure, Stop, Deny, destructive. Always with `[x]` and a word. |
| `on-danger` | `#fdfcfc` | `#0a0a0a` | `#0a0a0a` | Label on a `danger` fill. |
| `info` | `#aa3576` | `#edb2f1` | `#edb2f1` | Informational state, secondary technical emphasis. |
| `diff-add` | `#1d783c` | `#6bd586` | `#6bd586` | Added-line text and `+` gutter sign. |
| `diff-add-bg` | `#e7f9ea` | `#14361d` | `#14361d` | Added-line row wash. |
| `diff-del` | `#b82d35` | `#f17471` | `#f17471` | Deleted-line text and `−` gutter sign. |
| `diff-del-bg` | `#fceceb` | `#461516` | `#461516` | Deleted-line row wash. |
| `syntax-keyword` | `#c83d8b` | `#f799c6` | `#f799c6` | Keywords and primitives. |
| `syntax-string` | `#198b43` | `#96e3a6` | `#96e3a6` | Strings. See §5 for the chrome fallback. |
| `syntax-property` | `#d16427` | `#ffc1a4` | `#ffc1a4` | Properties. See §5 for the chrome fallback. |
| `syntax-type` | `#5230c2` | `#9e99f7` | `#9e99f7` | Types. |
| `syntax-constant` | `#007b80` | `#93e9f6` | `#93e9f6` | Constants and numbers. |
| `syntax-critical` | `#b82d35` | `#f29b96` | `#f29b96` | Critical tokens, invalid code. |
| `syntax-comment` | `#5c5c5c` | `#aeaeae` | `#aeaeae` | Comments. |
| `syntax-punctuation` | `{text}` | `{text}` | `{text}` | Punctuation and plain identifiers; alias of `text`. |

Brand constants (theme-independent):

| token | value | usage |
|---|---|---|
| `brand-ink` | `#201d1d` | OpenCode ink. Wordmark, splash, cover. |
| `brand-ink-deep` | `#0f0000` | Tint base of hairlines. Not a UI fill. |
| `brand-cream` | `#fdfcfc` | OpenCode cream — never replace with `#ffffff`. |
| `brand-gold` | `#fab283` | Identity colour of live agent activity. |
| `brand-terminal` | `#0a0a0a` | Base neutral of the session context. |

Rules:

- Dividers and ghost-button borders use `line` (1 px). Input and keycap borders
  use `line-strong`.
- `text` is for anything that must be read; `text-body` for body copy;
  `text-muted` for metadata; `text-weak` only for placeholders and disabled
  labels.
- `brand-gold` raw is never used as text on cream (1.9:1). Chrome uses the
  darkened `agent` token.
- `interactive` is for links and file references only. Never Apple
  `#007aff`-style blue as a brand fill.
- Status colour is never the only signal: `success`/`danger` are close in
  lightness (≈ 1.4:1), so glyph and word are mandatory.

## 5. Approved contrast fallbacks

The accepted plan documents two pairs below the 4.5:1 target. The CEO review
approved these as implementation requirements (not a WCAG certification):

1. **Light chrome syntax.** On `chrome`, `syntax-string` (4.3:1) and
   `syntax-property` (3.7:1) fall back to `text`. Code on chrome sits on `bg`
   only, never `bg-raised`. The `session` context is canonical for code and
   keeps full syntax colour.
2. **Session metadata on raised surfaces.** On `session`, `text-muted` is
   4.32:1 on `bg-raised`. Necessary metadata on `bg-raised` uses `text-body`
   or `text` instead. Session `text-muted` is only used on `bg` (5.0:1) and
   `bg-panel` (4.66:1).

Engineer verifies the actual foreground/background pairs before delivery. No
new supplier colours and no supplier correction are required.

## 6. Typography

One family: `mono` — `"Berkeley Mono", "IBM Plex Mono", ui-monospace,
SFMono-Regular, Menlo, monospace`. IBM Plex Mono (SIL OFL) is the face people
actually see and must be bundled with its licence; Berkeley Mono is commercial
and only used if separately licensed. `tokens.json` ships no font files.

Prose is mono too, as on opencode.ai. A system sans for long agent prose is an
accessibility setting, not a default.

| style | size | line height | weight | tracking | usage |
|---|---|---|---|---|---|
| `display` | 32 | 40 | 700 | −0.02em | Splash, onboarding, rare empty-state emphasis. |
| `title` | 22 | 32 | 700 | — | Screen titles. One line + ellipsis. |
| `section` | 17 | 24 | 700 | — | Section headings, sheet titles. |
| `body` | 16 | 24 | 400 | — | Agent prose, user prompts, settings copy. |
| `body-strong` | 16 | 24 | 500 | — | Row primary labels. |
| `control` | 15 | 22 | 500 | — | Button and link labels. |
| `label` | 12 | 18 | 700 | 0.06em | Transcript speaker labels (`YOU`, `AGENT`), group headers. Uppercase. |
| `tech` | 14 | 20 | 400 | — | Tool-call rows, commands, paths, status lines. |
| `code` | 13 | 20 | 400 | — | Code blocks and diffs. Never below 13. |
| `meta` | 12 | 18 | 400 | — | Timestamps, model ids, branches, line numbers. |
| `keycap` | 12 | 16 | 500 | — | Keycaps and compact technical chips. |

- Weights 400 / 500 / 700. No italics as styling. No marketing sizes in-app.
- Sizes are `sp`, dimensions are `dp`; the CSS `px` values are logical
  reference dimensions and must not be copied as physical pixels.
- Support Dynamic Type and Android font scale. Rows grow, they never clip.
- Truncation never hides security- or permission-relevant text.

## 7. Spacing

4 pt grid: `space-1` 4 · `space-2` 8 · `space-3` 12 · `space-4` 16 · `space-5`
20 · `space-6` 24 · `space-8` 32.

- Screen inset `space-4` on phones, `space-5` on large phones/tablets.
- Vertical rhythm inside a container uses one gap value; siblings never carry
  both margin and gap.

## 8. Radii, borders and depth

| token | value | usage |
|---|---|---|
| `radius-0` | 0 | Pages, lists, transcript, code, diffs, tool groups, app bar. |
| `radius-sm` | 4 | Buttons, keycaps, user prompt block, compact controls. |
| `radius-md` | 6 | Text inputs. |
| `radius-lg` | 8 | Composer field. The maximum in application content. |
| `shadow-none` | none | Every in-app surface. Android elevation 0. |

- No pills (except OS-owned controls), no 16–28 pt cards.
- Depth is spacing → surface shift → 1 px `line`. Never shadows.
- Focus: `focus-width` (2 px) solid `focus` ring, offset 2 px.
- Pressed: `primary-pressed`; rows use a `bg-panel` fill while pressed.
  Disabled: `bg-raised` fill + `text-weak` label, announced semantically.

## 9. Metrics

| token | value | usage |
|---|---|---|
| `hit-ios` | 44 | Minimum touch target on iOS. |
| `hit-android` | 48 | Minimum touch target on Android. |
| `row-min` | 52 | Minimum list row height. |
| `row-max` | 64 | Maximum two-line list row height. |
| `hairline` | 1 | Every divider and control border. |
| `focus-width` | 2 | Focus ring width, offset 2. |

`row-min`–`row-max` is nominal geometry: rows grow with large font scaling
rather than clipping permission, command or branch text.

## 10. Component patterns

Components use semantic tokens only. No component makes a network call, no
component embeds a server capability, and all user events are surfaced to the
consumer.

### L0 — offline fixture gallery (no server connection)

L0 covers: **theme, typography, metrics, Button, Status, KeyCap, ProjectRow,
SessionRow.** Fixtures demonstrate normal, pressed, disabled, focus, loading,
empty, error and offline states, with FR/EN copy externalised. The fixture
gallery must explicitly include `chrome-dark`, which no supplier preview
demonstrated.

**Button.** Flat, `radius-sm`, mono label, one `primary` per view.

| variant | fill / label | use |
|---|---|---|
| `primary` | `primary` / `on-primary` | The action the screen is for. Ink on chrome, gold in session. |
| `ghost` | `bg`, 1 px `line`, `text` | Secondary actions (Allow once, Connect server). |
| `danger` | `danger` / `on-danger` | Stop, Deny, Delete. Label states the consequence. |
| `text` | transparent, `text` | Tertiary (Cancel). |

- Height ≥ `hit-ios` / `hit-android`; horizontal padding `space-5`; label
  `control`.
- Pressed primary `primary-pressed`; disabled `bg-raised` + `text-weak`.
- Consumer provides a verb-first sentence-case label and an optional leading
  ASCII glyph (`[x]`, `■`).
- Do not use pills, tonal pastel fills, gradients, or icon-only consequential
  actions.

**Status.** Glyph + word, colour only reinforces.

| state | text | token |
|---|---|---|
| running | `● running` | `agent` |
| waiting | `○ waiting` | `text-muted` |
| completed | `[+] completed` (or `✓`) | `success` |
| attention | `[!] attention` | `warning` |
| failed | `[x] failed` | `danger` |

Style `tech`. No filled chips or coloured cards. Screen-reader label names the
subject and state: "Session Fix OAuth refresh, running".

**KeyCap.** `keycap` style, 1 px `line-strong`, `radius-sm`, padding 2 × 6.
Inline hints only (`esc`, `ctrl+p`). A keycap is not a button; if tappable,
wrap it in a ≥ 44/48 hit area. Do not colour keycaps or use them as tags.

**ProjectRow.** Full-width row, no card wrapper: status glyph (28 px column) →
project name → muted metadata → disclosure. Padding `space-3` × `space-4`, min
height `row-min`, 1 px `line` between rows. Title `body-strong`, one line +
ellipsis; meta `meta` in `text-muted`. Truncate the path from the left if
needed, never the branch. Glyph `>` idle, `●` in `agent` when a session runs.

**SessionRow.** Same geometry. Glyph from Status (`●` `agent`, `[!]` `warning`,
`[+]` `success`, `[x]` `danger`, `○` muted). Title → `agent · model · branch` →
relative time. A pending permission replaces the meta line with the reason
(`permission required · bash`) and is never truncated. Swipe actions (archive,
delete) are native; delete asks for confirmation.

### L3 — session components (with the features, after the L3 gate)

L3 covers: **Transcript, ToolCall, CodeBlock, Diff, StreamingCaret, Composer,
PermissionBanner, CommandSheet.**

**Transcript.** Full-width execution log on session `bg`, not a conversation of
bubbles. Each turn: a `label` line (`YOU`, `AGENT · build`) in `text-muted`,
then the body in `body`. User prompt is a `bg-panel` block, `radius-sm`, padding
`space-2` × `space-3`, same left edge as the agent. Agent output sits directly
on `bg`, no avatar, no container; reasoning in `text-body`, collapsed by
default. Tool calls sit inline between prose. File references use `interactive`.
Streaming uses a `█` caret in `agent` at the end of the live line.

**ToolCall.** One line per call, collapsed first:
`› name target stat`. Tap → `⌄` and output/diff follows inline; tap the target →
full-screen inspector. Row min height `hit-ios`, `tech` style. Name
`text-body`, target `text`, stat `meta`. Stat colours: `diff-add`/`diff-del`
counts, `success` `✓`, `danger` `[x]`, `agent` `● running`. Expanded output
uses CodeBlock/Diff directly under the row, no card. Accessibility label names
the tool, target and result.

**CodeBlock.** Opaque `bg-panel`, full-bleed, `radius-0`, `code` style (never
below 13). Horizontal scroll — source is never wrapped to fit the phone. Header
≥ `hit-ios` with path `meta` and copy/open/full-screen actions. Line numbers
`text-muted`, non-selectable. Syntax `syntax-*` only, with the chrome fallback
of §5. In chrome, code sits on `bg` only; session is canonical for code.

**Diff.** Unified diff in CodeBlock geometry. Every changed line carries a `+` /
`−` gutter sign **and** colour. Added: `diff-add` on `diff-add-bg`. Deleted:
`diff-del` on `diff-del-bg`. Context: `text` on `bg-panel`. Syntax highlighting
is dropped on changed lines. Header shows path and `+n −n`. Split view is out
of V1 scope. Never rely on red/green alone.

**StreamingCaret.** The execution indicator: `█` in `agent`, blinking 1 s
steps, optionally beside `● working`. Replaces circular spinners everywhere in
the session. Stops the instant execution stops. With reduced motion it is a
static block. Pair with a textual state for screen readers ("Agent working").

**Composer.** Docked at the bottom of the session, opaque, above the home
indicator and the keyboard — not a floating pill.
`[+] Ask anything… [model] [↑]`; while running, `Add instruction…` and `[■]`
Stop. Field `bg-panel`, 1 px `line-strong`, `radius-lg`, min height
`hit-android`, `body` style (16 avoids iOS zoom). Send `primary`/`on-primary`;
Stop `danger`/`on-danger` with screen-reader label "Stop agent". `[+]` opens
the CommandSheet. The session hides global tab navigation. Android uses
`imePadding()` + `navigationBarsPadding()`.

**PermissionBanner.** Sticky banner under the session app bar (or under the
triggering ToolCall) until resolved — never a toast. `bg-raised`, 1 px top rule
in `warning`, head `[!] Permission required · <tool>` in `warning` bold. The
full command in `text`, `tech` style, wrapping allowed, never truncated.
Actions are exactly the server-provided decisions (Deny `danger`, Allow once,
Allow session `ghost`); no Allow is pre-selected and no scope is invented.
`role="alert"`. The Inbox lists pending permissions across sessions.

**CommandSheet.** The one sheet for slash commands, model, agent, attachments,
session actions and branch selection. Opaque `bg-raised` content styled as
OpenCode; rows as ProjectRow with `hit-ios` height. Search field on top (`/`
prefix), `radius-md`, `bg`. Selected `●` in `agent`, others `○`. Native
presentation (iOS detents, Android ModalBottomSheet) without any glass or
native material behind the list.

## 11. Brand guidelines

- Official marks only, imported byte-for-byte from the supplier attachments
  (see [`design-system/logos/`](../design-system/logos) and
  [`design-system/README.md`](../design-system/README.md)). Never redraw,
  recolour or crop them.
- `opencode-logo-*` — the pictogram (240 × 300): light `#211E1E` frame /
  `#CFCECD` core; dark `#F1ECEC` frame / `#4B4646` core. App icon uses the
  square variant (300 × 300).
- `opencode-wordmark-*` — two-tone block wordmark; "open" `#656363` light /
  `#B7B1B1` dark, "code" `#211E1E` light / `#F1ECEC` dark. Splash, onboarding,
  About, empty and connection states only.
- `opencode-wordmark-simple-*` — single-ink wordmark (black on light, white on
  dark) for widths < 120 px or monochrome contexts.
- `-light` files go on `chrome`; `-dark` files on `chrome-dark` and `session`.
- Never use the wordmark as the app icon. No emoji, mascots, robot imagery,
  photography, gradients, glowing orbs or sparkle motifs. The content — code,
  diffs, commands — is the imagery.

## 12. Accessibility

- Guaranteed pairs: `text`/`bg` (16.3:1 chrome and chrome-dark, 17.1:1
  session), `on-primary`/`primary` (16.3:1 both chromes, 11.1:1 session),
  `on-danger`/`danger` (5.9:1 chrome, 6.1:1 session).
- Never colour alone: `[!] Permission required`, `[x] Failed`, `+`/`−` in
  diffs.
- Screen-reader labels describe the action and its result, not the widget
  ("Tool read, src/auth.ts, completed", "Session Fix OAuth refresh, running").
- Support Dynamic Type / Android font scale and honour reduced motion (static
  caret, no transitions).
- Truncation never hides security- or permission-relevant text.

## 13. Assets, sources and verification

- `design-system/source/tokens.json` — token set v1, imported byte-for-byte.
- `design-system/source/design-system.json` — original supplier manifest,
  preserved unmodified; its logo sizes differ from the real attachments and
  the discrepancy is recorded.
- `design-system/logos/` — the eight official marks.
- `design-system/references/` — the 14 supplier previews plus Cover; reference
  only, not runtime code.
- `design-system/SOURCES.sha256` — local SHA-256 of every imported file.
- Run `scripts/verify-design-system-sources.sh` to re-verify all imported
  sources against these hashes.

The imports are verified at the byte level. Native rendering, screenshots and
device behavior are verified separately by the implementation work, not by this
document.

## 14. Never

Chat bubbles · AI avatar · floating orb or giant FAB · gradient CTA · glass
behind technical content · Material You tonal palette · colourful dashboard
cards · radius > 8 · shadow hierarchy · decorative semantic colour · permission
in a toast · tool call as a big card · wrapped source code · a generic sans for
the chrome · Liquid Glass or any native material behind CMP content.