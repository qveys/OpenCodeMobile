# Architecture — OpenCode Mobile

Status: **partial**. This document currently covers only the server-profile
import mechanism (resolving `docs/THREAT-MODEL.md` T8, tracked by OPE-29),
permission approval confirmation (resolving T2, tracked by OPE-25), local
cache encryption at rest (resolving T3, tracked by OPE-26), and server
identity verification (resolving T1, tracked by OPE-24).
The full system architecture — component structure, data flow, API
boundaries, deployment model — is the scope of OPE-5 and is not yet written;
it depends on the tech-stack decisions in OPE-4. This file will be expanded
in place once OPE-5 lands; do not treat it as complete until that note is
removed.

## Server profile import (deep link / QR)

A "server profile" is the app's stored record of one self-hosted OpenCode
Server: host, port, TLS/fingerprint info, and (if present) a credential used
to authenticate REST/SSE calls (see `docs/THREAT-MODEL.md` §3.1, B1, B2).
Profiles can be created two ways:

1. **Manual entry** — the user types the server address directly in-app.
2. **Import** — a deep link or QR code encodes the same information so the
   user doesn't have to type it. QR is purely a transport for the deep
   link: scanning a QR code decodes to the same URI the app's deep-link
   handler consumes, so both paths share one implementation and one review
   screen.

### Link format

- Prefer platform **verified links** (Android App Links / iOS Universal
  Links backed by the project's own verified domain) over a bare custom URL
  scheme. A custom scheme (e.g. `opencodemobile://`) can be registered by
  any other installed app on Android, which could let a malicious app
  intercept or spoof "import" links; a verified HTTPS link cannot be
  hijacked this way. If a custom scheme is also shipped (e.g. as a QR
  fallback for platforms/flows where verified links don't apply), it goes
  through the exact same review flow below — it is not treated as a
  lower-friction path.
- The link carries the server `host`, `port`, an optional display `label`,
  and an optional TLS fingerprint for trust-on-first-use verification (see
  T1 in the threat model). It must not carry a long-lived bearer credential
  in the URL itself — deep links can end up in browser history, share
  sheets, clipboard managers, and OS-level link logs. If pairing needs to
  hand over a secret, the link carries a short-lived, single-use pairing
  code that the app exchanges for a credential over a direct TLS connection
  to the target host, immediately after the user confirms the import (see
  below) — the code is not usable on its own to reach the server, and it
  expires quickly whether or not it's used.
- Unrecognized or malformed import links (missing host, unparseable) are
  discarded silently — they never partially populate or clear an existing
  profile.

### Mandatory review before persisting (T8 mitigation)

An import link **never** writes a profile directly. It only opens a
dedicated, in-app **"Import server profile"** review screen. This screen is
the sole path by which an imported profile is persisted, and it enforces:

- **Full target disclosure.** The exact host and port (and fingerprint, if
  present) from the link are shown in full before any action is available.
  No field is elided, truncated in a way that hides the real host, or
  auto-filled into a state the user could mistake for "already trusted."
- **Explicit, distinct confirmation.** Persisting requires an explicit
  foreground tap on the review screen (e.g. "Add server"). No link
  parameter can auto-confirm, auto-navigate past this screen, or otherwise
  cause a profile to be saved without that tap — the same principle applied
  to notification-based permission approval in T2.
- **No silent overwrite.** If the imported host/port matches an existing
  stored profile (or the link otherwise targets an existing profile's
  identity), the screen does not present a generic "Add server" action. It
  switches to an explicit **"Update existing server?"** comparison view
  that shows the old and new host/fingerprint side by side, and requires a
  separate, distinctly labeled confirmation from "add new." A profile is
  never replaced as a side effect of importing a link with the same name or
  ID alone.
  This applies uniformly regardless of the open decision on single- vs.
  multi-profile support (see `docs/THREAT-MODEL.md` §1); in either mode,
  every existing profile is subject to the same no-silent-overwrite rule.
- **One pending import at a time.** If a second import link arrives while a
  review screen is already pending confirmation, it does not queue behind
  or silently replace the pending one; the new link is discarded and the
  user must re-trigger the import if they intended the second one.
- **No bypass of first-connect identity verification.** Confirming an
  import does not substitute for the server-identity checks applied to
  manually entered profiles (T1) — TLS/fingerprint verification and the
  "server identity changed" warning apply identically to imported and
  manually entered profiles on first (and every subsequent) connection.

These requirements apply to every source of an import link: in-app QR
scanner, OS share sheet, and cold/warm app launch via a link.

## Permission approval confirmation (foreground + authenticated)

Resolves `docs/THREAT-MODEL.md` T2 and the open product decision on
"permission approval from a push notification."

A tool-call permission request is server state (`docs/THREAT-MODEL.md` §3,
flow 4): the OpenCode Server emits a request, the app surfaces it, and the
user's decision is sent back and executed on the user's machine. Because a
mistaken or spoofed "approve" directly authorizes code execution on that
machine, a bare notification action is never sufficient to finalize it.

### Notification role: surface, not authorize

- A pending permission request always triggers a local notification, but the
  notification carries **no inline "Approve" action**. It is informational
  and deep-links into the app's dedicated confirmation screen; the only
  action attached to it is "Deny," described below.
- Lock-screen / notification-shade quick actions never include "Approve."
  This holds regardless of OS (Android heads-up/quick-reply actions, iOS
  notification actions) and is not user-configurable back to "on" — see
  Rationale.
- "Deny" *is* offered as a lock-screen/notification action, because denying
  is the safe, reversible direction: it can, at most, cause the agent to ask
  again, never authorize anything. It still requires the device to be
  unlocked per normal OS notification-action behavior; it does not require
  foreground app confirmation, since it grants no capability.

### Mandatory foreground confirmation before approving (T2 mitigation)

Approving is only ever finalized from the in-app **confirmation screen**,
reached by tapping the notification or by opening the app directly (e.g.
from an in-app pending-approvals list). This screen enforces:

- **App in foreground.** The approve action is only enabled while the app is
  the foreground, focused activity/scene. If the app is backgrounded (a
  call comes in, the user switches apps, the OS shows a system dialog, or
  the screen locks) before the tap registers, the pending confirmation state
  is discarded, not queued — the user must re-open the request and start
  the review over. This defeats tapjacking/overlay attacks and OS quick
  actions that fire without a genuine foreground review, and stray taps
  that land after a background/foreground transition.
- **Full content disclosure.** The screen renders the complete command (or,
  for a file-write tool call, the full diff) the agent is requesting
  permission for — never a truncated summary, a title only, or a
  previously-cached rendering that might not match the current request
  content. Long content scrolls in place; it is not summarized or elided to
  fit the screen.
- **Explicit, distinct confirmation.** Approving requires an explicit tap on
  an "Approve" control on this screen. It is never the default/pre-focused
  action, is not triggered by a generic "OK"/dismiss gesture, and is
  visually and positionally distinct from "Deny" to reduce accidental-tap
  risk (same principle as the "distinct confirmation" rule for server
  profile import, T8).
- **Biometric re-authentication gate.** Confirming an approval additionally
  requires a platform biometric (or device-credential fallback) check via
  the `expect`/`actual` biometrics API, immediately before the decision is
  sent — not once per app session, once per approval. This is the default,
  not an opt-in; a settings toggle may relax it only down to "foreground
  confirmation without biometrics," never down to "notification-only."
  (This directly answers the open product decision: notification-only
  approval is not offered as a mode.)
- **Request-bound, single-use decision.** The confirmation screen carries
  the server-issued permission-request ID and a content hash of what it is
  displaying; the approve/deny call back to the server includes both. A
  request that was already decided, superseded, or whose content changed
  since it was fetched cannot be approved from a stale screen — the app
  re-fetches and re-renders before allowing the tap to submit (ties into
  T6's single-use/no-replay requirement on the same IDs).

### Rationale

Tapjacking/overlay spoofing on Android and lock-screen/notification-shade
quick actions on both platforms can trigger a notification action without
the user ever seeing, or genuinely consenting to, what they're approving.
Because a tool-call approval directly authorizes arbitrary code execution
on the user's own development machine (per T2's impact rating), it is
treated as a high-consequence, effectively irreversible action — the same
bar applied to persisting an imported server profile (T8) — rather than a
low-friction notification action.

## Local cache encryption at rest

The local SQLDelight cache (`docs/THREAT-MODEL.md` T3, B5) holds session
transcripts, prompts, and file diffs — potentially proprietary code — for the
disposable, offline-read-only store already fixed in `docs/TECH-STACK.md`.
It is never a second source of truth: it can be wiped and rebuilt from the
server at any time. This resolves the open decision flagged in `BOOTSTRAP.md`
and `docs/TECH-STACK.md` (Open Decisions #2) in favor of encrypting it.

### Requirements (mandatory)

- **Encrypted at rest, not relying on OS full-disk encryption alone.**
  Full-disk encryption only protects data while a device is powered off; it
  does nothing once the device is unlocked or a malicious/compromised app on
  the same device can reach the app's sandboxed storage — exactly the T3
  scenario (lost/stolen unlocked device, malware). The cache must not be
  recoverable in plaintext by reading the raw DB file off the device.
- **Excluded from unencrypted OS/cloud backups.** The cache DB (and its
  `-wal`/`-shm` companion files) must never end up in a plaintext-readable
  iCloud or Android auto-backup archive, independent of whether the DB
  itself is encrypted.
- **No user-facing passphrase.** The product has no separate app-level login
  or passcode — auth is Keychain/Keystore plus optional biometrics only (B2)
  — so the encryption key must be machine-generated and stored in the
  platform key store, never something the user types or remembers.
- **Key loss is a cache miss, not a fatal error.** Because the cache is
  disposable, a missing or invalidated key (e.g. a Keystore entry
  invalidated by biometric re-enrollment) must be handled by wiping and
  rebuilding the cache from the next server snapshot — never a crash or a
  manual-recovery flow for the user.

### Approach

- **Android**: back SQLDelight's Android driver with a SQLCipher-encrypted
  database (e.g. `net.zetetic:sqlcipher-android` via a
  `SupportSQLiteOpenHelper.Factory`). Generate a random passphrase on first
  run and store it through the platform's Keystore-backed secure storage —
  the same Keychain/Keystore `expect`/`actual` boundary already planned for
  server credentials (B2), not a separate mechanism.
- **iOS**: SQLCipher's iOS build requires replacing the system `libsqlite3`
  that SQLDelight's Kotlin/Native driver links against, which is a
  disproportionate native-toolchain dependency to take on before the KMP
  module scaffold (OPE-30) exists. Default instead to the platform's
  built-in Data Protection: create the cache database inside a location
  protected by `NSFileProtectionCompleteUnlessOpen` (or `.complete`, to be
  confirmed once offline/background read behavior is implemented), which
  ties decryption to the device passcode/Secure Enclave without the app
  managing its own key material. If SQLCipher-for-iOS later proves
  tractable within the KMP/CocoaPods toolchain, it may replace this for
  symmetry with Android — this decision sets the minimum bar, not a
  ceiling.
- **Both platforms**: exclude the cache DB and its `-wal`/`-shm` files from
  backups — Android via data-extraction/backup-exclusion rules
  (`android:dataExtractionRules`, within the already-fixed API 31+ floor)
  and iOS via `NSURLIsExcludedFromBackupKey` set on the file URL at
  creation time.

### Alternatives considered

- **Rely on OS full-disk encryption alone (status quo)** — rejected: it does
  not defend against the T3 scenario itself (unattended unlocked device,
  malware with app-sandbox access), which is exactly what app-level
  encryption is for.
- **SQLCipher on both platforms** — preferred in principle for one
  mechanism and one mental model, but the iOS native-linking cost is
  disproportionate this early; revisit once the module scaffold and iOS
  build tooling (OPE-30) exist.
- **User-set app passcode deriving the key** — rejected: it adds an
  authentication surface the product doesn't otherwise have, for a cache
  the architecture already treats as disposable and rebuildable.

### Status

Decision resolved; the driver wiring above is scoped for implementation once
the KMP/CMP module scaffold (OPE-30) and the shared/security `expect`/`actual`
layer exist. Treat this section as fixed guidance for that work, the same as
the T8 import-review flow above.

## Server identity verification (T1)

Resolves `docs/THREAT-MODEL.md` T1. Without a way to verify *which* OpenCode
Server it is talking to, the app cannot distinguish the user's own
self-hosted instance from a rogue server on the same LAN (malicious AP,
ARP/DNS spoofing) or a maliciously imported profile (T8). An impersonating
server can harvest the auth credential on first contact and then present
fake session/tool-call data, leading the user to approve attacker-crafted
tool calls believing they're reviewing their own agent's work.

### Requirements (mandatory)

- **Trust-on-first-use (TOFU) fingerprint verification, not CA-based
  validation, is the primary mechanism.** Self-hosted OpenCode servers on a
  LAN or Tailscale typically run with a self-signed certificate or no TLS at
  all; requiring a publicly-trusted CA certificate would push most users
  either to plaintext HTTP or to disabling certificate validation outright —
  both worse than TOFU. This mirrors the model users already understand from
  SSH host keys.
- **Fingerprint = SHA-256 of the leaf certificate's SubjectPublicKeyInfo
  (SPKI)**, computed at TLS handshake time. It is stored per server profile
  in the same Keychain/Keystore boundary already used for credentials (B2) —
  not a separate mechanism — so fingerprint trust is isolated per profile the
  same way T8 requires credential/identity isolation between profiles.
- **Identity is verified before the credential is ever sent.** The trust
  check (pinned-fingerprint match, or a fresh TOFU acceptance) must complete
  before the adapter attaches the `Authorization` header to any request on
  that connection — on first connect as much as on every reconnect. It must
  not be possible for a request carrying the credential to reach the network
  before this check passes.
- **First connect**: the exact fingerprint is shown to the user (rendered as
  colon-separated hex, the same convention as SSH/TLS tooling), and
  persisting it as the profile's pinned value — and sending the credential
  for the first time — requires an explicit, distinct foreground
  confirmation. This is the same "explicit, distinct confirmation" bar
  applied to import review (T8) and permission approval (T2); a generic
  "OK"/dismiss gesture does not count.
- **Every subsequent connect**: the presented fingerprint is compared to the
  pinned value before the handshake completes. On mismatch, the app **fails
  closed**: it does not fall back to an unverified connection, does not offer
  a low-friction "trust anyway" option alongside the normal reconnect flow,
  and does not silently retry. It shows a full-screen, non-dismissible-by-
  swipe "server identity changed" warning — visually and textually distinct
  from a generic connection error — stating this may indicate the server has
  been replaced or is being impersonated, and requires the user to explicitly
  review the new fingerprint and re-confirm (functionally a new TOFU
  acceptance) before any further request, including a retry, is sent.
- **Plaintext HTTP profiles get a persistent, explicit warning, not an
  icon.** If a profile has no TLS, there is no certificate to pin — the app
  cannot verify server identity or protect the credential in transit at all.
  This is disclosed with visible text (not solely a small padlock-style
  icon) at profile creation and every time that profile is used to connect,
  stating plainly that the credential and all traffic are readable to
  anyone on the network path. Whether plaintext HTTP is restricted to
  private LAN/Tailscale address ranges by default is a connection-policy
  question left to OPE-5/the L1 connection design, not decided here.
- **Imported profiles are not exempt.** Per the T8 section above, an import
  link may optionally carry a fingerprint, but confirming an import never
  bypasses this section's checks — first-connect display and every-connect
  verification apply identically regardless of how the profile was created.

### Approach

- The tech stack (`docs/TECH-STACK.md`) fixes Ktor as the HTTP/SSE client
  underlying `OpenCodeV2Adapter`/`EventProcessor`. Ktor has no single
  cross-platform certificate-pinning API, so the check is implemented at the
  platform engine layer behind the same `expect`/`actual` boundary already
  used for Keychain/Keystore and biometrics — the adapter itself stays
  platform-agnostic.
  - **Android (OkHttp engine)**: a custom `X509TrustManager` (or a
    per-profile-reloaded OkHttp `CertificatePinner`) computes the SPKI
    SHA-256 of the presented leaf certificate and compares it to the
    profile's pinned value before the handshake completes.
  - **iOS (Darwin engine)**: a `URLSession` challenge delegate
    (`urlSession(_:didReceive:completionHandler:)`) performs the equivalent
    SPKI comparison against the chain from `SecTrustCopyCertificateChain`
    before allowing the connection to proceed.
- Credential attachment (the `Authorization` header interceptor) is
  structured to depend on "this connection has passed the identity check,"
  rather than being independently wired — so it is not possible to
  accidentally reorder the two and leak the credential on an unverified
  connection.

### Alternatives considered

- **CA-based TLS validation only** — rejected as the sole mechanism: see
  Requirements above; it doesn't fit the self-hosted, typically self-signed
  or plaintext LAN deployment this product targets.
- **A single certificate/key hardcoded in the app** — not applicable: there
  is no one OpenCode Server instance to pin against. Each user runs their
  own, so trust has to be established per profile at pairing time (TOFU),
  not baked into the app binary.
- **Soft/dismissible warning on fingerprint mismatch (e.g. a toast)** —
  rejected: a dismissible warning is exactly the failure mode T1 describes —
  the user waves through an impersonator without a genuine review. The
  warning must block further requests, not merely inform.

### Status

Decision resolved; the pinning/verification logic above is scoped for
implementation once the KMP/CMP module scaffold (OPE-30) and the
OpenAPI-generated client (OPE-31) exist, landing inside
`OpenCodeV2Adapter`'s connection/handshake path. Because this touches
shared/security and the adapter/generated-client boundary, any implementing
PR requires the reinforced review path (Code Reviewer + Security Engineer +
explicit owner approval) per `BOOTSTRAP.md`. Treat this section as fixed
guidance for that work, the same as the T8 and T3 sections above.

## Speech-to-text dictation and on-device enforcement (T5)

Resolves `docs/THREAT-MODEL.md` T5 (B3, Data flow #3). Dictation allows users to
speak prompts (which may contain proprietary code, file paths, or credentials)
into the composer for transmission to their OpenCode Server. Mobile platform
speech-to-text APIs often default to cloud-assisted recognition (routing audio to
Apple or Google servers), which would silently violate the product's fundamental
no-telemetry promise.

### Requirements (mandatory for Version 1)

- **Strictly on-device speech recognition in V1.** Dictation must use exclusively
  on-device recognition APIs. No audio or transcript may leave the device toward
  any third party (Apple, Google, etc.).
- **iOS enforcement**:
  - Check `SFSpeechRecognizer.supportsOnDeviceRecognition` for the active locale.
  - Explicitly set `SFSpeechAudioBufferRecognitionRequest.requiresOnDeviceRecognition = true`
    on every recognition request. If on-device recognition is unsupported or models
    are missing, the request must fail closed rather than falling back to cloud.
- **Android enforcement**:
  - Instantiate recognizers strictly via `SpeechRecognizer.createOnDeviceSpeechRecognizer(Context)`
    (API 31+ project floor). Never use `SpeechRecognizer.createSpeechRecognizer(Context)`.
  - Do not rely on `RecognizerIntent.EXTRA_PREFER_OFFLINE` as an enforcement mechanism,
    as Android documentation defines it as a non-binding hint.
  - Verify runtime availability via `SpeechRecognizer.isOnDeviceRecognitionAvailable(Context)`
    (API 33+) or handle `ERROR_LANGUAGE_NOT_SUPPORTED` / `ERROR_LANGUAGE_UNAVAILABLE`
    as an unavailable state without network fallback.
- **UX when unavailable**:
  - If on-device recognition is unavailable for the current device/OS/locale, the
    mic button in the composer is disabled (not hidden) with a clear message:
    "Dictation isn't available on this device for [language]. Type your prompt instead."
  - The keyboard remains the standard fallback input method.
- **Privacy policy disclosure**: The privacy policy (owned by Technical Writer)
  must explicitly state that V1 dictation is processed strictly on-device and
  disabled when on-device recognition is unavailable.

### Extensibility & Future Evolution (Post-V1)

While Version 1 strictly mandates on-device platform recognition, the application
architecture must remain extensible for future evolution beyond V1:

- **Interface abstraction**: The shared platform layer defines a clean
  `DictationProvider` interface that yields a closed availability model
  (`Available`, `Unavailable(reason)`). The composer interacts solely with this
  interface.
- **V1 implementation**: Only `PlatformOnDeviceDictationProvider` is active in V1,
  guaranteeing zero network-fallback capability in the shipped binary.
- **Post-V1 evolution options**:
  1. *Server-side OpenCode transcription*: A future release may allow the user's
     self-hosted OpenCode server to transcribe audio (e.g. via local Whisper on
     the host). Spoken audio is transmitted solely over the existing authenticated
     TLS connection (B1) to the user's server, preserving zero third-party
     telemetry while expanding language/device support.
  2. *User-configured external endpoints*: Users may optionally configure custom
     STT services with their own API credentials, gated by explicit opt-in and
     prominent privacy notices.

### Status

Decision resolved for Version 1; binding guidance for lot L4 ("Mobile
integration") implementation per `ROADMAP.md`. See full specification in
`docs/adr/on-device-speech-to-text.md`.

