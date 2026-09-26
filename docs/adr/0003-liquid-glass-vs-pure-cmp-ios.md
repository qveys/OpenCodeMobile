# ADR 0003: Liquid Glass vs. Pure Compose Multiplatform on iOS (OP1)

- Status: accepted
- Date: 2026-09-24
- Driven by: OPE-34, recording Cahier des charges d'architecture v1.0 §4 D4, §14.1 OP1 (attached to OPE-2)

## Context

The architecture spec (§4 D4) mandates: "UI partagée en Compose Multiplatform ; expect/actual pour notifications, biométrie, secure storage, dictée" (shared UI in Compose Multiplatform; expect/actual for notifications, biometrics, secure storage, dictation).

§14.1 OP1 identifies an open decision created by the interaction of Q1 (UI choice) and Q3 (iOS 26+ target):

| Option | Description |
|--------|-------------|
| (a) | Renoncer à Liquid Glass — Compose Multiplatform renders its own iOS 26 UI end-to-end |
| (b) | Chrome iOS en SwiftUI natif hébergeant le contenu CMP — native SwiftUI chrome hosting CMP content |

Impact analysis in §14.1: "SUPPOSÉ : CMP dessine son propre rendu et n'expose pas le matériau natif iOS 26. (b) réintroduit une UI iOS partiellement native." (CMP draws its own rendering and does not expose native iOS 26 material. Option (b) reintroduces a partially native iOS UI.)

This decision directly affects who owns navigation and lifecycle on iOS:
- Option (a): CMP owns navigation/lifecycle end-to-end; `iosApp` is a thin host providing only platform actuals (Keychain, notifications, `SFSpeechRecognizer`)
- Option (b): SwiftUI owns the outer chrome/navigation; CMP content is embedded; navigation split between native and shared layers

## Decision

**Adopt Option (a): Pure Compose Multiplatform UI on iOS, without native Liquid Glass materials.**

This decision was confirmed by the commanditaire on 2026-09-24 via the accepted decisions addendum to OPE-2 ("Décisions acceptées et reprise L0"):
> "OP1 : UI CMP pure en V1, sans Liquid Glass natif. Formaliser l'ADR dans OPE-34 ; le POC iOS reste à prouver."

## Rationale

1. **Architectural consistency with D4** — D4 explicitly chooses shared UI via Compose Multiplatform. Introducing a native SwiftUI chrome layer contradicts the "shared UI" mandate and reopens the previously resolved divergence where Proposition B had "MUST NOT share UI through Compose Multiplatform" (removed per D4).

2. **Single navigation/lifecycle owner** — Pure CMP means `shared/*` ViewModels and the Compose `NavHost` own all navigation state. `iosApp` remains a minimal host (as documented in `iosApp/README.md`), providing only platform `actual` implementations via `expect/actual`. No split-brain navigation.

3. **No partial-native maintenance burden** — Option (b) would require maintaining SwiftUI chrome, bridging lifecycle events, and coordinating navigation state across the native/CMP boundary for the lifetime of the product. Option (a) keeps the iOS host minimal and stable.

4. **iOS 26+ target (§4 D13)** — Compose Multiplatform 1.7+ targets iOS 16+ and renders with its own Skia-based engine. It does not adopt Apple's Liquid Glass materials automatically. The spec acknowledges this ("CMP dessine son propre rendu"). Adopting Liquid Glass would require a native layer that CMP cannot provide.

5. **Design-system independence** — The design system (`design-system/` module) defines tokens, typography, and components in CMP. A native chrome layer would either duplicate that system in SwiftUI or create visual divergence.

6. **POC still required** — The commanditaire mandated an iOS proof-of-concept to verify CMP rendering performance and platform integration on iOS 26+. This ADR records the *architectural* decision; the POC validates technical feasibility (tracked separately in L0).

## Consequences

### Positive
- **Unified navigation/lifecycle**: Single source of truth in CMP `NavHost` + ViewModels across Android and iOS
- **Minimal `iosApp`**: Stays as documented — Swift entry point + `actual` implementations only
- **Design-system coherence**: One component library, one token set, one visual language
- **No native/CMP bridge for navigation**: Eliminates a whole class of sync bugs

### Negative / Risks
- **No native Liquid Glass materials**: The app will not automatically adopt Apple's iOS 26 glass morphism effects. Visual parity with native iOS 26 apps requires CMP-level theming work.
- **Platform convention gaps**: Certain iOS-specific patterns (e.g., large title navigation bars, search bars, toolbar behavior) must be implemented in CMP rather than inherited from SwiftUI.
- **POC validation required**: If CMP rendering on iOS 26 proves insufficient (performance, text rendering, accessibility), a revisit would require a new ADR.

### Neutral / Follow-up
- **L0 scope unchanged**: The roadmap L0 already included "Liquid Glass vs. pure Compose Multiplatform decision (OP1)" as a deliverable. This ADR fulfills it.
- **L4 (Mobile Integration) scope unchanged**: Dictation, notifications, biometrics, task-switcher masking, and "erase everything" remain platform `actual` implementations via `expect/actual` — unaffected by this decision.
- **Design-system work (OPE-21/OPE-22)**: Must provide iOS-26-appropriate component variants in CMP (e.g., navigation bar, tab bar, modal sheets) since no native chrome supplies them.

## Commanditaire Sign-off

Recorded in the accepted decisions addendum to OPE-2 (document `decisions-acceptees`, revision `ca9fd348-0871-48c4-94ec-81b6d2299d48`, 2026-09-24):
> "OP1 : UI CMP pure en V1, sans Liquid Glass natif. Formaliser l'ADR dans OPE-34 ; le POC iOS reste à prouver."

## References

- Cahier des charges v1.0 §4 D4, §14.1 OP1 (OPE-2 document `opencode-mobile-cahier-des-charges-d-architecture-v1-0-converge`)
- Accepted decisions addendum (OPE-2 document `decisions-acceptees`)
- Roadmap L0 deliverable: "Liquid Glass vs. pure Compose Multiplatform decision (OP1)"
- `iosApp/README.md` — documents the minimal host architecture this decision preserves
- ADR 0001 (monorepo module scaffold) — established `iosApp` as thin host with per-module framework linking