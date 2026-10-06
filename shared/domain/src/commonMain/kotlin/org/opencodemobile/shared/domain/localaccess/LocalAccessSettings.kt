package org.opencodemobile.shared.domain.localaccess

/**
 * Device-local access protections (cahier des charges §7.3, Q12 → B).
 *
 * These are **device preferences**, never server state and never credentials.
 * They decide what the app reveals on screen and which platform protections it
 * asks the OS for. They are deliberately *not* part of server authentication.
 *
 * - [optionalBiometricsEnabled] only turns local convenience re-authentication
 *   on or off. Turning it off gives access to **no** server credential, because
 *   the server session is authenticating against the server, not the device.
 * - [multitaskMaskingEnabled] is **on by default**: the OS app-switcher snapshot
 *   must not reveal the transcript. Hiding the snapshot is not the same as
 *   blocking screenshots.
 * - [screenCaptureBlockingEnabled] is **off by default**: masking must never
 *   silently enable capture blocking. A user MAY opt in where the platform
 *   distinguishes the two (on Android, `FLAG_SECURE` blocks both).
 */
public data class LocalAccessSettings(
    /** Whether the app MAY offer biometric / device-credential re-auth locally. */
    public val optionalBiometricsEnabled: Boolean = false,
    /** Whether the app-switcher snapshot is masked. §7.3 / Q12 → B default. */
    public val multitaskMaskingEnabled: Boolean = true,
    /** Whether the platform screen-capture blocking is requested. Default off. */
    public val screenCaptureBlockingEnabled: Boolean = false,
) {
    public companion object {
        /**
         * §7.3 / Q12 → B: mask the multitask preview by default, keep capture
         * blocking off until the user explicitly asks for it, and treat
         * biometrics as optional.
         */
        public val Default: LocalAccessSettings = LocalAccessSettings()
    }
}

/**
 * Persistence port for [LocalAccessSettings].
 *
 * Implementations live in `shared/security` (`androidMain` / `iosMain`) on top
 * of the platform's device-local key/value store. The port is synchronous
 * because the values are tiny and are read on the app-lifecycle path (the
 * app-switcher cover), where a suspending read would be too late.
 */
public interface LocalAccessSettingsStore {
    /** The persisted settings, with [LocalAccessSettings.Default] for any unset key. */
    public fun load(): LocalAccessSettings

    /** Persists [settings] so the next launch observes them. */
    public fun save(settings: LocalAccessSettings)
}
