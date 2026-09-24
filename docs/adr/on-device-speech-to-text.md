# ADR: Force on-device speech recognition for dictation, or disable it

- **Status**: Accepted — binding for the L4 "Mobile integration" implementation (`ROADMAP.md`); not yet implemented (no KMP/CMP module scaffold exists yet, see OPE-30).
- **Resolves**: `docs/THREAT-MODEL.md` T5 (High) — "Speech-to-text dictation may silently use a cloud STT provider (Apple/Google) on some OS versions/locales, sending spoken prompts (which may contain code or secrets) off-device — contradicting the product's 'no code/prompt telemetry' promise."
- **Trust boundary**: B3 (App process ↔ OS speech-to-text service).
- **Data flow**: #3 in `docs/THREAT-MODEL.md` §3, "Prompt dictation".
- **Deciders**: Engineer, informed by the Security Engineer's threat model.

## Context

OpenCode Mobile's entire product premise is "no product backend, no code/prompt
telemetry — the OpenCode Server is the sole source of truth" (`BOOTSTRAP.md`).
Dictation is a named V1 capability (spoken prompts transcribed to text and sent
to the user's own OpenCode Server), planned for delivery lot **L4 — Mobile
integration** (`ROADMAP.md`, requirement V1-10), implemented behind the
project's `expect`/`actual` platform layer (`docs/TECH-STACK.md`).

Both mobile platforms expose speech recognition APIs that can transparently use
a cloud backend (Apple's or Google's servers) depending on OS version, locale,
network state, or even just which entry point the app calls — without any
explicit code decision to do so. A user dictating a prompt may be reading
proprietary source, secrets, or internal file paths aloud. If any of that audio
or its transcript leaves the device to a third party, it directly breaks the
"no telemetry" promise this product is built on, regardless of what the rest
of the architecture does correctly (B1 TLS, B2 Keychain/Keystore, B7 log
redaction, etc. — see the other T-issues resolving those). This is why T5 is
rated High even though it is "just" a UI feature: the blast radius is a
privacy-policy-breaking silent leak, not a crash or a UX bug.

The risk is specifically that the *default*, easiest-to-reach API on both
platforms does not guarantee on-device-only processing — it has to be forced,
and the forcing has to be verified per platform, per OS version, and per
locale, because on-device support is not universal.

## Decision

Dictation must use **only** recognition entry points that the platform
contractually documents as on-device-only. If the platform cannot guarantee
that for the user's current locale/OS version, dictation must be **disabled
with a clear in-app message** — never silently fall through to a
cloud-capable entry point.

### iOS

- Use `Speech.framework` (`SFSpeechRecognizer` +
  `SFSpeechAudioBufferRecognitionRequest`), scoped to the app's active locale
  (`SFSpeechRecognizer(locale:)`).
- Before starting a recognition session, check the **instance property**
  `SFSpeechRecognizer.supportsOnDeviceRecognition` for that locale. If `false`,
  do not attempt recognition — go straight to the disabled state below.
- If `true`, set `SFSpeechAudioBufferRecognitionRequest.requiresOnDeviceRecognition = true`
  explicitly before starting the task. This is the actual enforcement point:
  without it, iOS is permitted to use server-based recognition even when
  on-device support exists, entirely at Apple's discretion. `requiresOnDeviceRecognition`
  is what turns "may be on-device" into "must be on-device, or fail" — a
  recognition task started this way errors out rather than silently falling
  back to network transcription.
- Treat `supportsOnDeviceRecognition == false` and any recognition error
  raised because on-device processing isn't possible (e.g. required language
  model not installed/available) identically: disabled state, not a retry
  without the flag.

### Android

- Use the in-app `SpeechRecognizer.createOnDeviceSpeechRecognizer(Context)`
  entry point (available from API 31, the project's floor per
  `docs/TECH-STACK.md`), not `SpeechRecognizer.createSpeechRecognizer(Context)`
  and not the `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` system dictation
  intent.
  - `createSpeechRecognizer` is explicitly documented as capable of using a
    network-based backend at the OS/OEM's discretion.
  - `RecognizerIntent`'s `EXTRA_PREFER_OFFLINE` is a **hint**, not a
    guarantee — Android's own documentation does not promise offline-only
    behavior from it, so it must not be relied on as the enforcement
    mechanism for this requirement. It is not sufficient on its own and must
    not be used as a substitute for `createOnDeviceSpeechRecognizer`.
- Where available (API 33+), gate on
  `SpeechRecognizer.isOnDeviceRecognitionAvailable(Context)` before starting.
  On API 31–32, where that capability check doesn't exist, attempt
  `createOnDeviceSpeechRecognizer` and treat `RecognitionListener.onError`
  with `ERROR_LANGUAGE_NOT_SUPPORTED` / `ERROR_LANGUAGE_UNAVAILABLE` (or
  equivalent "no on-device model for this language" errors) as unavailable —
  not as a signal to retry through the network-capable recognizer.
- OEM variation is real here (not every Android device ships or downloads an
  on-device model for every language): the capability check must be a live
  runtime query, not a build-time assumption from the API level alone.

### Shared platform-layer contract (`expect`/`actual`)

- The `expect` API exposed to shared code must model availability as a
  closed set: **available (on-device, active locale)** or **unavailable
  (reason)**. There is no third state that means "available via network" —
  that path must not exist in the `actual` implementations at all, so it
  can't be reached by a future change to error handling or a missed edge
  case. This is a stronger guarantee than "prefer on-device": the capability
  to call a cloud-backed recognizer should not be linked into the app.
- Capability must be re-checked whenever the app's active locale changes
  (V1 ships French + English per `docs/TECH-STACK.md`; on-device support can
  differ between the two on either platform).

### UX when unavailable

- The dictation entry point (mic button on the composer) is disabled, not
  hidden without explanation, with a clear, short message — e.g. "Dictation
  isn't available on this device for [language]. Type your prompt instead."
- No silent spinner-then-nothing, no automatic retry against a different
  recognizer. The keyboard remains the fallback input method (the composer
  already supports typed input independent of dictation).

### Privacy policy disclosure

Per T5's mitigation and the company's documentation ownership split
(`BOOTSTRAP.md`: Technical Writer owns public docs including the privacy
policy, but never authors technical contracts), the privacy policy must state
plainly, once L4 ships:

- Dictation, when available, is processed entirely on-device; no audio or
  transcript is sent to Apple, Google, or any third party.
- When on-device recognition isn't available for the user's device/language,
  dictation is disabled rather than silently using a cloud provider.

This ADR is the technical source of truth the Technical Writer should draft
that disclosure from when the feature is actually implemented — the policy
should describe shipped behavior, not a forward-looking promise, so this note
is a flag for that lot rather than a request to write the policy text now.

## Consequences

- Dictation will be **unavailable on some device/OS-version/locale
  combinations** where the platform has no on-device model — this is an
  accepted, deliberate trade-off: a disabled feature with a clear message is
  correct product behavior here, a silent privacy violation is not.
- No network-connectivity requirement is introduced for dictation itself
  (on-device recognition works offline once any required language model is
  present); this is a net UX positive, not just a privacy one.
- Engineering follow-up when L4 starts: this decision should be written as an
  explicit rejection criterion for that lot's code review (e.g. "any
  dictation code path that can reach a non-on-device-guaranteed recognition
  API is an automatic rejection"), alongside the project's existing
  systematic-rejection list in `BOOTSTRAP.md`.
- No code changes accompany this ADR: the KMP/CMP module scaffold and the
  `expect`/`actual` platform layer do not exist yet (repository is at lot L0
  per `ROADMAP.md`, tracked by OPE-30/OPE-31/OPE-32/OPE-33). This document is
  the binding spec the Engineer implementing dictation in L4 must follow.

## Alternatives considered

- **Use `EXTRA_PREFER_OFFLINE` / default recognizers and accept occasional
  cloud fallback** — rejected: this is precisely the silent-violation
  scenario T5 identifies; "prefer" is not "require", and the product's
  no-telemetry promise is absolute, not best-effort.
- **Always disable dictation entirely (never attempt on-device)** — rejected:
  unnecessarily discards a genuinely private, useful capability on the many
  device/locale combinations where on-device recognition does work well.
- **Ship dictation without a documented enforcement mechanism, rely on
  post-hoc QA to catch cloud fallback** — rejected: cloud-vs-on-device
  routing is not reliably observable by testing on a single device/locale,
  and the failure mode is a silent, unlogged privacy leak — this has to be
  prevented structurally in the API contract, not caught after the fact.
