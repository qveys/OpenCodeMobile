# L4 — Mobile integration: delivered behaviour

<!--
  OPE-277 (Technical Writer). Describes the **shipped** behaviour of lot L4
  (`ROADMAP.md`), not a forward-looking promise. Every section points at the
  technical decision it restates (ADR / `docs/ARCHITECTURE.md`) and at the code
  that implements it. This document authors no technical contract: where a
  design question is open, it is linked, not answered here.
  User-facing privacy statements live in [`PRIVACY.md`](PRIVACY.md).
-->

- **Lot**: L4 — Mobile integration (`ROADMAP.md`), gate: real-device recipe on
  Android + iOS ([OPE-278](/OPE/issues/OPE-278)).
- **Scope delivered**: V1-10 (OS dictation), V1-13 (local notifications) and
  OP4 (no approval from a notification), optional biometrics, task-switcher
  masking, "Erase everything" (OP5).
- **Status of this document**: written 2026-10-06, after the security reviews
  of the lot landed.

## 1. What L4 is, in one paragraph

L4 is the lot that makes the app behave like a phone app rather than a browser
tab: speech input, lock-screen presence, a device-local identity gate, a hidden
app-switcher snapshot, and a local "burn everything" action. Every one of those
five features is constrained by a security decision taken *before* the code was
written, and every constraint below was verified by an independent security
review, not only by the implementer's tests.

| Feature | Requirement | Decision of record | Implementation |
| --- | --- | --- | --- |
| Voice dictation | V1-10 | [ADR — on-device speech-to-text](adr/on-device-speech-to-text.md) (threat T5) | `features/composer` `AndroidOnDeviceDictationProvider` / `IosOnDeviceDictationProvider`, port in `shared/domain` |
| Local notifications | V1-13 | [ADR 0005](adr/0005-v1-open-decisions-op1-op5.md) §OP4 | `shared/application` `NotificationPolicy`, `LocalNotificationCoordinator`, sinks in `androidApp` / `iosAppHost` |
| Optional biometrics | §7.3 | `docs/ARCHITECTURE.md` §"Permission approval confirmation" (threat T2) | `shared/security` `AndroidBiometricAuthenticator` / `IosBiometricAuthenticator` |
| Task-switcher masking | §7.3 (Q12 → B) | `docs/ARCHITECTURE.md` §local-access | `androidApp` `PrivacyShield`, `iosApp` `ContentView` overlay |
| "Erase everything" | §7.3 `PROPOSÉ (A)`, OP5 | [ADR 0009](adr/0009-erase-everything-v1.md) | `shared/application` `EraseEverythingCoordinator`, `features/settings` `EraseEverythingController` / `EraseEverythingScreen` |

Local-access details (biometrics, masking, capture blocking) are documented in
[`local-access.md`](local-access.md); this page does not duplicate them.

## 2. Voice dictation (V1-10) — on-device or disabled

**Shipped behaviour.** Dictation is processed **entirely on the device**. No
audio, no transcript and no fragment of dictated text is sent to Apple, Google
or any third party. When on-device recognition is not available for the active
locale — no installed language model, an OS with no on-device recogniser, an
unwired platform binding — **dictation is disabled**. The composer disables the
mic button with a short FR/EN message and the keyboard remains usable.

There is **no cloud fallback path in the binary**. On Android the app constructs
recognizers only through `SpeechRecognizer.createOnDeviceSpeechRecognizer`;
`SpeechRecognizer.createSpeechRecognizer` and the system
`ACTION_RECOGNIZE_SPEECH` intent are absent, and an architecture test fails the
build if either reappears. On iOS the provider gates on
`SFSpeechRecognizer.supportsOnDeviceRecognition` **and** sets
`requiresOnDeviceRecognition = true` on every request, which is what turns "may
be on-device" into "on-device or fail". Capability is a live runtime query, not
an API-level assumption, because on-device model coverage differs per language,
device and OEM.

The microphone permission is requested **at first use**. Dictation writes into
the composer draft only: sending the prompt to the server requires a distinct
user tap, and the `DictationProvider` port has no send capability at all.

**Consequence to state plainly**: dictation is unavailable on some
device/OS/locale combinations. That is the accepted trade-off of the ADR, not a
defect to be worked around. Post-V1 evolutions (server-hosted transcription on
the user's own OpenCode server, user-configured external endpoints) are
architecturally enabled but **are not part of the shipped V1**.

**Evidence.** [OPE-272](/OPE/issues/OPE-272) → PR #85; security review
[OPE-276](/OPE/issues/OPE-276) → **PASS**, no Critical/High finding.

## 3. Local notifications (V1-13) — state only, never authority

**Shipped behaviour.** Notifications are **strictly local**, derived from server
state the app already knows. There is no push service, no notification server
and no foreground service; nothing was added server-side to work around the
platform limitation that a fully idle app cannot be relied upon to notify.

Four triggers, all derived from server state: a pending tool-call permission, a
pending agent question, a finished turn (`busy → idle`), and a session the
server put into its error/retry state.

**No approval from a notification (OP4).** The shared action vocabulary is
`NotificationAction { Open, Deny }` — there is no `Approve` member, so the type
system, not a runtime check, is what forbids a notification from authorising
anything. `Deny` is kept because denying is the safe, reversible direction: it
can at most make the agent ask again. Approving a permission requires the
**foregrounded, authenticated** confirmation screen with its own
biometric/device-credential gate.

Notification bodies deliberately never carry the command, its arguments or file
diffs, because a notification is visible on a locked screen; the exact content
is shown only on the authenticated in-app surface (threat T13). On Android the
notification is marked `VISIBILITY_PRIVATE`.

A notification is a **signal, not a state**: if the platform refuses it or the
app is killed, only the signal is lost — permissions, questions and statuses
are rebuilt from the server (cache + reconciliation, `GET /question`, realtime
snapshot).

**Evidence.** [OPE-273](/OPE/issues/OPE-273) → PR #84; OP4 also covered by the
[OPE-276](/OPE/issues/OPE-276) review → **PASS**.

## 4. Optional biometrics

**Shipped behaviour.** Biometrics are **optional** and **off by default**. They
are a convenience re-confirmation on protected screens and an optional extra
gate on "Erase everything" — they are **never a substitute for server
authentication**, and disabling them grants access to no credential. The gate is
**fail-closed**: no enrolled authenticator, or an unwired implementation, yields
`Unavailable`, which is never reported as success.

See [`local-access.md`](local-access.md) for the platform implementations, the
defaults and the test locations.

## 5. Task-switcher masking (default on)

**Shipped behaviour.** An opaque cover hides the app content before the OS takes
the app-switcher snapshot, on both platforms, **by default**
(`multitaskMaskingEnabled = true`). The transcript, sessions and messages are
therefore not visible in the app-switcher preview.

Capture blocking (screenshots / screen recording) is a **separate**, **default-off**
user setting, and masking never turns it on as a side effect — on Android,
`FLAG_SECURE` blocks screenshots and recording too. There is no iOS user switch
for capture blocking in V1.

See [`local-access.md`](local-access.md) for the platform details and the
real-device recipe lines.

## 6. "Erase everything" (OP5) — ADR 0009

**Shipped behaviour.** One explicit, confirmed, user-initiated action erases, on
the device only:

1. the **live authenticated session** — `disconnect()` drops the in-memory
   credential permit and closes the transport first, so the erased credential
   cannot keep authorising a request;
2. the **server credential** in the SecureStore (Keystore / Keychain);
3. the **server profile** and its **trust-on-first-use identity pin**;
4. the **encrypted cache** — the database, its `-wal` / `-shm` / `-journal`
   sidecars, and the cache key material held in the OS keystore;
5. the **local notifications** posted by the app.

Explicitly **not** erased: your **server** (no session, message or permission is
touched), non-sensitive UI preferences (language, theme, masking, capture
blocking, optional biometrics), and LLM provider keys, which the app never
stores.

Triggering is structural, not conventional: there is no code path from the idle
UI state straight to the erase — a confirmation state is mandatory, and the
coordinator has exactly one production caller, guarded by that state. It can
never be invoked from a notification, a deep link or a background task. The
biometric re-authentication, when enabled, is an **additional** gate: a user who
never enrolled can still erase. After the erase the app returns to the connection
screen, never auto-reconnects, and the cache is rebuilt **from the server only**.

Erasure is best-effort per category but never silent: each category is attempted
independently and any residue is reported and named in the UI. A partial erase is
never presented as a success.

**Known gap, documented rather than hidden.** On iOS the cache stack is not
composed in this build, so no cache artifact exists on the device; the iOS
composition root binds an `EmptyLocalCacheEraser`, which is honest because there
is nothing to erase. When the iOS cache stack lands, only that binding changes —
the coordinator already calls the port.

**Evidence.** Decision [OPE-271](/OPE/issues/OPE-271) → ADR 0009 (PR #82);
implementation [OPE-275](/OPE/issues/OPE-275) → PR #83; security review
[OPE-282](/OPE/issues/OPE-282) → findings F1–F6; re-review
[OPE-283](/OPE/issues/OPE-283) → **PASS**.

## 7. Terminology

The product ships FR and EN from V1 with no hardcoded user-visible string, so
this document and the UI use the same words. Where a term exists in the app's
string catalogs, it is reproduced here verbatim rather than re-invented.

| Concept | English (UI) | French (UI) |
| --- | --- | --- |
| Dictation button / unavailability | "Dictate" / "Dictation isn't available on this device for this language." | « Dicter » / « La dictée n'est pas disponible sur cet appareil pour cette langue. » |
| Prompt | "Type a prompt…" | « Saisissez une consigne… » |
| Local access | "Local access" | « Accès local » |
| Optional biometrics | "Optional biometrics" | « Biométrie optionnelle » |
| App-switcher masking | "Hide content in the app switcher" | « Masquer le contenu dans le sélecteur d'apps » |
| Capture blocking | "Block screenshots and screen recording" | « Bloquer les captures d'écran et l'enregistrement » |
| Erase everything | "Erase everything…" | « Tout effacer… » |
| Erased categories | "Live session", "Server credential", "Server profile", "Identity pin", "Encrypted cache", "Notifications" | « Session active », « Credential serveur », « Profil serveur », « Pin d'identité », « Cache chiffré », « Notifications » |
| Credential | "credential" (untranslated) | « credential » (untranslated) |

Two terms are deliberately **not** translated, and should not be: **credential**
(the app calls it that in both catalogs) and **pin** for the trust-on-first-use
identity pin.

## 8. References

- [`PRIVACY.md`](PRIVACY.md) — what the app stores, what leaves the device, and the no-telemetry promise
- [`local-access.md`](local-access.md) — local-access protections, masking, capture blocking, real-device recipe
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — security design, cache encryption at rest, SecureStore, redacted logging, dictation enforcement
- [`THREAT-MODEL.md`](THREAT-MODEL.md) — T2 (biometrics), T3 (cache at rest), T5 (dictation), T13 (notifications)
- [`adr/on-device-speech-to-text.md`](adr/on-device-speech-to-text.md) — the binding dictation decision
- [`adr/0005-v1-open-decisions-op1-op5.md`](adr/0005-v1-open-decisions-op1-op5.md) — OP2 (cache encryption), OP4 (no notification approval), OP5 (individual decisions for `PROPOSÉ` items)
- [`adr/0009-erase-everything-v1.md`](adr/0009-erase-everything-v1.md) — the "Erase everything" decision
- [`../ROADMAP.md`](../ROADMAP.md) — lot L4 scope and its exit gate
