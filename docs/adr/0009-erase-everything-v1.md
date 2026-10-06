# ADR 0009: « Tout effacer » (Erase Everything) — V1 scope and behaviour (OP5)

- **Status**: Accepted
- **Date**: 2026-10-06
- **Decided by**: CEO
- **Related issues**: OPE-271 (this decision), OPE-275 (implementation), OPE-2 (specification and OP5 register)
- **References**:
  - *Cahier des charges d'architecture v1.0* §3.1 (V1 scope), §7.3 (secrets and local access), §7.4 (privacy), §8.1 (cache and offline)
  - [ADR 0005](0005-v1-open-decisions-op1-op5.md) — OP5: no bulk validation of `PROPOSÉ` items; each one needs an explicit, individual decision
  - [ADR 0003](0003-liquid-glass-vs-pure-cmp-ios.md) §L4 (mobile-integration scope unchanged)
  - [ADR: on-device speech-to-text](on-device-speech-to-text.md) — sibling L4 decision
  - `ROADMAP.md` — L4 "Mobile integration" scope; `docs/ARCHITECTURE.md` — local cache encryption at rest, SecureStore, server profile
  - `docs/THREAT-MODEL.md` — T3 (cache at rest), T8 (persisted profile)

## 1. Context

The specification's §7.3 table lists « **Tout effacer** : secrets, cache,
notifications, profil » with origin **`PROPOSÉ (A)`**. Per OP5 (ADR 0005 §2), a
`PROPOSÉ` item is not a defect and not an oversight — but it must not be
validated by bulk, and it stays `PROPOSÉ` until an explicit, individual
decision is recorded. A full-text search of the backlog and docs
(2026-10-06) found no such decision.

The commanditaire opened lot **L4 — Mobile integration** on 2026-10-06
(`ROADMAP.md`), and the L4 graph carries [OPE-275](/OPE/issues/OPE-275) for the
implementation. OPE-275 is **blocked** on this decision issue
([OPE-271](/OPE/issues/OPE-271)) and must not be implemented before the decision
is rendered.

The OP5 default rule (ADR 0005 §1) is: **choose the simplest and cheapest option
unless there is a strong reason not to.** This ADR applies that rule.

Relevant implementation facts, verified in the current `main` tree:

- The storage primitives already exist: `SecureServerProfileStore.clear(...)`,
  `SecureServerCredentialStore.remove(...)`,
  `TofuServerIdentityCoordinator.forget(...)`, and
  `SessionCacheWriter.wipeServer(...)`. "Erase everything" **composes existing
  wipe operations**; it does not require a new storage layer.
- `docs/ARCHITECTURE.md` defines the encrypted SQLDelight cache (SQLCipher on
  Android, OS Data Protection on iOS) and states the cache is disposable and
  rebuildable from the server.
- The app stores server credentials and can authorise tool-call execution on
  the user's own server. That raises the value of an in-app way to remove local
  sensitive data without uninstalling the app or clearing OS app data.

## 2. Decision (Version 1)

**Retained in the V1 perimeter, delivered in lot L4.** The §7.3 `PROPOSÉ` item
« Tout effacer » is **VALIDATED** for V1. It is **not** deferred to Post-V1, and
OPE-275 is retained (not cancelled).

### 2.1 Data erased — exact scope

One action erases, on the device only:

1. **SecureStore — credentials and secrets.** The stored server credential /
   access secret for the profile, via the SecureStore abstraction (Keystore on
   Android, Keychain on iOS). Nothing secret remains in preferences, files, or
   logs (§7.3 MUST NOT).
2. **Server profile.** The locally stored profile (address, identity metadata)
   and the trust-on-first-use server identity **pin**, so a future connection
   re-establishes trust explicitly rather than reusing a stale pin.
3. **Encrypted cache.** The SQLDelight cache database **and its SQLite sidecar
   files `-wal` and `-shm`**, plus the cache passphrase / key material held in
   the OS keystore. The wipe is not complete while a `-wal`/`-shm` residue can
   still hold plaintext row images.
4. **Local notifications.** All notifications posted by the app are cancelled,
   so erased session/permission content does not linger on the lock screen or
   in the notification shade.

**Out of scope (explicitly not erased):**

- Non-sensitive UI preferences (theme, language/locale). They contain no
  secret, no cache content, and no server data; erasing them would surprise the
  user without a privacy benefit.
- The **server** is never modified. "Tout effacer" is local-only; it never
  deletes sessions, messages, or permissions on the OpenCode Server.
- The app does not store provider LLM keys (§7.2: never stored client-side), so
  there is nothing to erase there.

### 2.2 Trigger

- **Explicit user action only**, reached from the app's settings/session
  surface. The action is **never** automatic, never silent, and never
  invocable from a notification, deep link, widget, or background task.
- Before confirmation, the UI states **exactly what will be erased**; after
  completion, it reports what was erased.

### 2.3 Confirmation

- **Reinforced destructive confirmation**: a dedicated confirmation step, not a
  single tap (e.g. a distinct destructive-confirmation dialog naming the four
  categories).
- **Biometric re-authentication is an optional additional gate**, available
  when the user has enrolled and enabled biometrics. It is **not** a hard
  requirement: §7.3 makes biometrics optional and says they never replace
  server authentication. A user who did not enrol can still erase.

### 2.4 Behaviour after erasure

- Clear all in-memory state, then return to the **connection / credentials
  screen** (unauthenticated). No auto-reconnect with the erased credential.
- The cache is **rebuilt only from the server** on the next successful connect
  (§8.1: cache is disposable, server wins all conflicts).
- The app must never silently retry, or leave a UI path that implies the erased
  data still exists.

### 2.5 Security constraints (non-negotiable)

- No secret or erased content is written to logs at any step (§8.3).
- The wipe covers DB + `-wal`/`-shm` + key material (§2.1.3).
- **No regression of T3/OP2**: the cache stays encrypted at rest while it
  exists; the erase produces no plaintext residue.
- Tests must assert that **all stores are empty after the wipe** (secure store,
  profile store, identity pin, cache artifacts) and that the action **cannot be
  triggered automatically**.

## 3. Justification

- **User trust / privacy.** A self-hosted remote-control app that holds server
  credentials and can execute tool calls should let its user remove all local
  sensitive data from inside the app. Deferring this to Post-V1 leaves a privacy
  gap with no cheap compensating fix.
- **Cheap.** The underlying wipe primitives already exist (see §1); the
  incremental work is composition, UI, notification cancellation, and tests —
  not new storage architecture. This satisfies ADR 0005's "simplest and
  cheapest" default; there is no strong reason to pay for it later instead.
- **Safe direction.** Erasing one's own local data is reversible by
  reconnecting, and it is user-initiated. It is the same "safe direction" logic
  OP4 used to keep only reversible actions off the lock screen.
- **Already sequenced.** `ROADMAP.md` already names "erase everything" inside
  the L4 scope; this decision confirms it instead of silently dropping it.

## 4. Consequences

- **[OPE-275](/OPE/issues/OPE-275) is unblocked and retained** (`blockedByIssueIds`
  cleared). Its acceptance contract is now frozen by §2: erase exactly the four
  categories, reinforced confirmation with optional biometrics, return to the
  connection screen, empty-store tests, and no T3/OP2 regression.
- **[OPE-277](/OPE/issues/OPE-277) (L4 docs / privacy policy)** must describe the
  erase behaviour defined here; **[OPE-278](/OPE/issues/OPE-278) (device
  acceptance)** must include a pass line for it.
- **The §7.3 `PROPOSÉ` item status changes explicitly to `VALIDATED (V1)`** —
  see the OPE-2 decision register addendum of 2026-10-06. This ADR is the
  explicit individual decision OP5 requires; no other `PROPOSÉ` item is touched.
- **No lot gate is opened by this ADR.** L4 delivery still ends at the
  commanditaire's explicit sign-off; L5 and L6 remain closed.

## 5. References

- *Cahier des charges d'architecture v1.0* §3.1, §7.3, §7.4, §8.1, §8.3
- [ADR 0005](0005-v1-open-decisions-op1-op5.md) — OP5
- [ADR 0003](0003-liquid-glass-vs-pure-cmp-ios.md) — L4 scope unchanged
- [ADR: on-device speech-to-text](on-device-speech-to-text.md) — sibling L4 decision
- `ROADMAP.md` — L4 lot and gate; `docs/ARCHITECTURE.md` — cache encryption at rest
- `docs/THREAT-MODEL.md` — T3, T8
- [OPE-271](/OPE/issues/OPE-271), [OPE-275](/OPE/issues/OPE-275)
