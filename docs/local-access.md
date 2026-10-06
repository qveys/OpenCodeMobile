# Local access protections (§7.3)

Two device-local protections are implemented for the L4 lot (OPE-274):

1. **Optional biometrics** — Face ID / Touch ID / `BiometricPrompt`.
2. **App-switcher preview masking** — enabled by default (Q12 → B).

They are device preferences, stored by the app and never synced. They are
separate from server authentication: **turning biometrics off gives access to no
server credential**.

## Biometrics (optional, fail-closed)

The domain port and its result type already existed:
`shared/domain/.../permission/BiometricAuthenticator.kt`
(`BiometricResult.Succeeded / Cancelled / Failed / Unavailable`, `Unavailable`
being the fail-closed value). This lot adds the missing platform
implementations; it does not reinvent the port:

| Platform | Implementation | Backed by |
| --- | --- | --- |
| Android | `shared/security/src/androidMain/.../biometric/AndroidBiometricAuthenticator.kt` | `android.hardware.biometrics.BiometricPrompt` + `BiometricManager`, `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` |
| iOS | `shared/security/src/iosMain/.../biometric/IosBiometricAuthenticator.kt` | `LAContext` with `LAPolicyDeviceOwnerAuthentication` (biometry, passcode fallback) |

Fail-closed contract:

- availability is checked **before** any prompt; anything other than
  `BIOMETRIC_SUCCESS` / a `true` `canEvaluatePolicy` returns `Unavailable`;
- no authenticator or a not-wired implementation is **never** success;
- user/system cancellation returns `Cancelled`, any other outcome returns
  `Failed`/`Unavailable` — never `Succeeded`;
- a fresh `LAContext` is built per call on iOS (a context caches its own
  success).

Platform tests:

- Android mapping: `shared/security/src/androidUnitTest/.../biometric/AndroidBiometricErrorMappingTest.kt`.
- Android on-device fail-closed: `.../androidInstrumentedTest/.../AndroidBiometricAuthenticatorInstrumentedTest.kt`
  (self-skips when an authenticator is enrolled, i.e. the real prompt path).
- iOS mapping: `shared/security/src/iosTest/.../biometric/IosBiometricErrorMappingTest.kt`.

The optional setting only gates *convenience* re-authentication. The approval
gate (V1-06, T2) belongs to L3 and keeps its own mandatory biometric /
device-credential check; this setting cannot relax it.

## App-switcher masking (default on) and capture blocking (default off)

`LocalAccessSettings` (domain) fixes the defaults:

- `multitaskMaskingEnabled = true`
- `screenCaptureBlockingEnabled = false`
- `optionalBiometricsEnabled = false`

Persisted per device by `shared/security` (`AndroidLocalAccessSettingsStore`
over `SharedPreferences`, `IosLocalAccessSettingsStore` over `NSUserDefaults`),
with the shared defaulting logic in `PersistentLocalAccessSettingsStore`. The
FR/EN settings screen lives in `features/settings` and reads/writes only the
domain port.

**Do not confuse the two protections.** Masking hides the app-switcher snapshot;
it must not turn on capture blocking. On Android, `FLAG_SECURE` blocks
screenshots and screen recording as well, which is why it is only set when the
user explicitly opts in (criterion 4).

| Platform | Masking | Capture blocking |
| --- | --- | --- |
| Android | Opaque cover `View` added to `android.R.id.content` in `Activity.onPause`, removed in `onResume` (`androidApp/.../privacy/PrivacyShield.kt`) | `WindowManager.LayoutParams.FLAG_SECURE`, only when the user enabled it |
| iOS | SwiftUI overlay on `scenePhase` `.inactive` / `.background` (`iosApp/iosApp/ContentView.swift`), preference read through the `iosAppHost` bridge `multitaskMaskingEnabled()` | No user-facing switch in V1 (iOS cannot block captures independently of such an overlay) |

## Real-device recipe lines (L4)

Android:

1. Install the debug app on a device/emulator, connect a server, open a session.
2. **Masking (default).** Open the app switcher. The snapshot shows the opaque
   cover, not the transcript. Return to the app: the transcript is back.
3. **Capture blocking (default off).** With the setting off, a screenshot
   (Power+Volume) is allowed and the app-switcher cover still appears.
4. **Capture blocking (opt-in).** Enable "Block screenshots and screen
   recording", then screenshot: the OS refuses and the screenshot is black. The
   app-switcher cover still appears.
5. **Biometrics.** On a device with no enrolled biometric and no device
   credential, the optional biometric prompt is unavailable (fail-closed); with
   one enrolled, enabling the setting prompts for it and a cancellation returns
   to the app without granting anything.

iOS:

1. Install the debug app on a device/simulator, connect a server.
2. **Masking (default).** Swipe up to the app switcher: the snapshot is the
   black cover with the lock glyph. Returning restores the UI.
3. **Biometrics.** On a device with Face ID / Touch ID (and a passcode) the
   setting prompts for it; on a device with neither, the gate is unavailable
   (fail-closed). `NSFaceIDUsageDescription` must be present — it is declared in
   `iosApp/iosApp/Info.plist`.
