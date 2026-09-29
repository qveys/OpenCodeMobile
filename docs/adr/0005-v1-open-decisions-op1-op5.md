# ADR 0005: Closing the V1 open decisions OP1–OP5

- **Status**: Accepted
- **Date**: 2026-09-29
- **Recorded by**: CEO
- **Decided by**: the project owner, on 2026-09-24, through two distinguished
  chains. The distinction matters: OP2 is **not** a fully ratified owner decision.
- **Provenance**:
  - **OP1, OP3, OP4, OP5** — the project owner accepted the *Cahier des charges*
    plan revision `86cef530` (confirmation `deebe9bb`); the `decisions-acceptees`
    record of the specification issue (OPE-2) traces those arbitrations, and its
    addendum states that it *prevails for OP1/OP3/OP4*. ADR 0003 (OP1) is the
    model to follow here: it cites that addendum rather than claiming a fresh
    approval.
  - **OP2** — decided by an agent on OPE-26 (2026-09-24), then the owner accepted
    the OPE-26 *plan* (interaction `b66e2626`). The OPE-2 `decisions-acceptees`
    register **explicitly carves OP2 out** and defers its current state to
    OPE-26/OPE-28, warning that a `done` alone does not prove validation or
    tested code. OP2 is therefore recorded here as **partially closed** (see
    §2, §3), not as a ratified owner decision.
- **Related Issue**: OPE-102
- **References**:
  - *Cahier des charges d'architecture v1.0* §14.1 (the five open points) and §3.1 (V1 scope)
  - [ADR 0003](0003-liquid-glass-vs-pure-cmp-ios.md) — OP1
  - [ADR: on-device speech-to-text](on-device-speech-to-text.md) — the dictation decision
  - `docs/ARCHITECTURE.md` — OP2, OP3, OP4 detailed designs
  - `docs/THREAT-MODEL.md` — T1, T2, T3, T5, T8

---

## 1. Context

The specification carried five open points (OP1–OP5, §14.1) left over from
questions the sponsor never answered (Q21). They were not cosmetic: OP2 and
OP4 are security-relevant, and OP3 changes the connection data model that L1
is about to build. Leaving them open meant every L1 design decision had a
branching factor nobody had priced, which is why the project stalled at the
L0/L1 boundary.

This ADR closes all five. It is the repo-side source of truth; the Paperclip
record on OPE-2 is the board-side one, and the two must not be edited apart
without updating both.

Default rule applied, per the mandate: **choose the simplest and cheapest
option, unless there is a strong reason not to**. Two of the five (OP2, OP4)
deviate from that default because the threat model rates their risk High and
an existing accepted design already rejected the cheaper option on the record.

## 2. Decision register

| ID | Point | Decision | Consequence |
|---|---|---|---|
| OP1 | Liquid Glass vs. pure Compose Multiplatform on iOS | **(a) Pure CMP UI in V1, no native Liquid Glass** — [ADR 0003](0003-liquid-glass-vs-pure-cmp-ios.md) | iOS chrome is CMP-rendered; no SwiftUI UI layer. The iOS POC remains an L1 verification item. |
| OP2 | Encryption of the SQLDelight cache | **(b) Application-level encryption, staged — partially closed in V1.** Android: SQLCipher with a Keystore-held passphrase (closes T3 on Android). iOS: OS Data Protection (`NSFileProtectionCompleteUnlessOpen`, `.complete` still to be confirmed) as the V1 bar, SQLCipher-for-iOS deferred. Design fixed in `docs/ARCHITECTURE.md` §"Local cache encryption at rest" | The cache ships in **L2**, not L1, so L1 carries no encryption work at all. **iOS is accepted with residual risk, not closed**: file-class protection keyed to the passcode covers a powered-off device or a filesystem image, but **not** malware with app-sandbox access nor an unlocked, stolen device — two of the T3 scenarios that motivate OP2. The residual risk must be carried into the acceptance criteria of OPE-107 and re-evaluated at `.complete` or SQLCipher-for-iOS. |
| OP3 | Server profiles in V1 | **(a) A single stored server profile** | L1 builds no profile-management UI. `ServerId` is **kept in the Domain model** even with one profile, so multi-profile stays a data-layer change, not a rewrite. Multi-profile moves to the Post-V1 P1 backlog. |
| OP4 | Approving a permission from a notification | **(a) Not offered in V1** — a notification only surfaces state; approving requires the foregrounded, authenticated confirmation screen (`docs/ARCHITECTURE.md` §"Permission approval confirmation (foreground + authenticated)") | No notification **approval** action in V1. The notification surface is read-only for approvals: the security design allows at most a "Deny" action (the safe, reversible direction), never "Approve". Keeps the high-consequence approval on a screen the user is actually looking at. |
| OP5 | The batch of `PROPOSED`-only requirements | **No bulk validation.** Each `PROPOSÉ` item keeps its `PROPOSÉ` status until an explicit, individual decision | `PROPOSÉ` is not a defect and not an oversight. Note the two `PROPOSÉ` items that do fall inside the MVP cut (V1-07 agent questions, V1-08 abort): they are **in scope by explicit sponsor decision recorded here**, not by bulk validation of the column. |

## 3. Justifications

**OP1** — Option (b) (native SwiftUI chrome hosting CMP content) would give up
"one UI codebase, one design system, one test story" for a visual effect on one
OS version, and CMP does not expose the native iOS 26 material anyway. Cheapest
and most consistent: (a). Full reasoning in ADR 0003.

**OP2** — The mandate's default was "OS-level protection only, defer" —
option (a) of *Cahier des charges* §14.1 OP2. That default is **rejected on the
record**: `docs/THREAT-MODEL.md` rates T3 *High* (impact High / likelihood
Medium), and OS full-disk encryption does nothing once the device is unlocked or
a compromised app on the same device reads the app sandbox — which is precisely
the T3 scenario.

The compromise adopted here is **asymmetric**, and only partially clears that
bar:

- **Android — closed.** SQLCipher with a Keystore-held passphrase encrypts the
  cache with app-managed key material, so the T3 scenarios above no longer read
  plaintext.
- **iOS — accepted with residual risk, *not* closed.** V1 uses the platform's
  Data Protection (`NSFileProtectionCompleteUnlessOpen`, with `.complete` still
  to be confirmed once offline/background read behaviour is implemented — see
  `docs/ARCHITECTURE.md` §"Local cache encryption at rest"). That yields
  file-class protection keyed to the device passcode: it protects a powered-off
  device and a filesystem image, but it does **not** protect against malware
  that already has app-sandbox access (the class key lives in the OS), nor
  against an unlocked, stolen device. Those are two of the T3 scenarios this
  ADR opens by rejecting OS-only protection. The residual risk is **accepted**
  for V1; it must be stated in the acceptance criteria of OPE-107 (SessionCache +
  "encryption at rest (OP2)") and re-evaluated when `.complete` is confirmed or
  SQLCipher-for-iOS lands.

SQLCipher-for-iOS native linking is the expensive half kept out of V1, and none
of the work starts until the cache exists in L2. The cache is disposable and
rebuildable from the server, so a lost key is a cache miss, never a failure.

**OP3** — A solo developer runs one server; the option-(b) UI (profile list,
switcher, per-profile pins and cache scoping) buys nothing at V1 and multiplies
the T1/T8 review surface. Cost of choosing (a) is kept near zero by retaining
`ServerId` in the Domain and scoping the cache `ServerId → ProjectId →
SessionId` as `docs/ARCHITECTURE.md` §3.4 already specifies.

**OP4** — A tool-call approval authorises arbitrary code execution on the user's
own machine. T2 treats it as high-consequence and effectively irreversible, the
same bar as persisting an imported profile (T8). A lock-screen or shade action
can be triggered by a tapjacking overlay and is not genuinely consented to. Not
offering it in V1 is both the cheapest and the only defensible option; V1-13
notifications remain MAY, and the notification surface may carry at most a
"Deny" action (the safe, reversible direction) — never an "Approve".

**OP5** — Bulk-validating a column of ~30 items that were never individually
reviewed would convert "not yet decided" into "approved without review", which
is exactly the failure mode the `PROPOSÉ` marker exists to prevent. Each such
item gets its own decision when it is reached; two of them reached the MVP cut
and are called out individually above.

## 4. Consequences

**Closed, except OP2.** Four of the five are closed (OP1, OP3, OP4, OP5); OP2 is
**partially closed** — Android is closed, iOS is accepted with the residual risk
stated in §3. The OP register holds no *undecided* item, and no architecture,
data-model, or UI decision in L1–L3 has a branching factor left to price, but the
iOS cache-protection bar is explicitly provisional. `ROADMAP.md` and
`docs/ARCHITECTURE.md` §9 are updated by this change.

**The MVP cut is L1 + L2 + L3**, i.e. the `ROADMAP.md` L3 scope V1-01…V1-09:
connect to a server (V1-01), complete the handshake (V1-02), enter or scan the
server (V1-03), list and manage sessions (V1-04), send a prompt and read the
streamed transcript (V1-05), and see and answer permission requests (V1-06),
**plus** V1-07 (pending agent questions) and V1-08 (abort) — the two `PROPOSÉ`
items that enter by individual decision recorded here (OP5) — **and** V1-09
(model/agent listing). The acceptance criteria are the *Critère d'acceptation*
column of *Cahier des charges* §3.1 (which runs V1-01…V1-14); §13 is the
"Règles absolues et Definition of Done" section and carries no requirement list.

**Deferred out of the MVP cut** (unchanged, still sequenced in the roadmap):

- L4 mobile integration — dictation (V1-10), local notifications (V1-13),
  biometrics, task-switcher masking. The dictation *decision* is already closed
  (on-device only, [ADR: on-device speech-to-text](on-device-speech-to-text.md));
  only the implementation is open.
- L5 files and diffs (V1-11, V1-12).
- L6 hardening, compatibility matrix, store publication.
- Post-V1: multi-profile, mDNS discovery, terminal, light editing.

**Not authorised by this ADR.** It closes scope questions; it does not open a
lot gate. Lots still start only on the owner's explicit sign-off for the
preceding lot, and the L0 gate is still governed by its own issue.

## 5. References

- *Cahier des charges d'architecture v1.0* §3.1, §13, §14.1 (OPE-2)
- [ADR 0003](0003-liquid-glass-vs-pure-cmp-ios.md) — OP1
- [ADR 0004](0004-architecture-dependency-rules-konsist.md) — enforcement context
- [ADR: on-device speech-to-text](on-device-speech-to-text.md) — dictation
- `docs/ARCHITECTURE.md` — "Local cache encryption at rest" (OP2), §3.4 (OP3),
  "Server profile import" (OP3), "Permission approval confirmation" (OP4), §9
- `docs/THREAT-MODEL.md` — T2, T3, T5, T8
- `ROADMAP.md` — lot sequencing and the MVP cut
