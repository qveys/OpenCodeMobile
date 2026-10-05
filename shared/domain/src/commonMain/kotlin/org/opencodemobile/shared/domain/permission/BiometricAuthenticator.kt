package org.opencodemobile.shared.domain.permission

/**
 * Outcome of a biometric / device-credential authentication attempt.
 *
 * Modelled explicitly (rather than a `Boolean`) so an implementation cannot
 * accidentally report a cancellation or an unavailable sensor as success.
 */
public sealed interface BiometricResult {
    /** The user authenticated successfully. */
    public data object Succeeded : BiometricResult

    /** The user cancelled or dismissed the prompt. */
    public data object Cancelled : BiometricResult

    /** The authentication failed (wrong biometric, too many attempts, ...). */
    public data class Failed(public val reason: String) : BiometricResult

    /**
     * No usable authenticator is present (no enrolled biometric and no device
     * credential), or the platform implementation is not wired. Callers must
     * treat this as "not authenticated", never as success.
     */
    public data object Unavailable : BiometricResult
}

/**
 * The platform biometric / device-credential gate (T2).
 *
 * `docs/ARCHITECTURE.md` §"Permission approval confirmation" requires a real
 * platform check **immediately before** an approval is sent, once per approval.
 * The application layer must consume this port rather than a caller-supplied
 * boolean; a boolean proves nothing.
 *
 * Implementations live in `shared/security` (`androidMain` / `iosMain`); the
 * interface lives in the domain so the application layer can depend on it.
 * Implementations must:
 * - prompt for biometric **or** device-credential authentication,
 * - never treat "no authenticator"/"not wired" as success (use
 *   [BiometricResult.Unavailable]),
 * - be safe to call from a coroutine, resuming with the user's decision.
 */
public interface BiometricAuthenticator {
    /**
     * Shows the authentication prompt and suspends until the user answers.
     *
     * [reason] is the short explanation shown in the prompt. Implementations
     * must not throw for a normal cancellation or failure; they return the
     * corresponding [BiometricResult].
     */
    public suspend fun authenticate(reason: String): BiometricResult
}

/**
 * Fail-closed default used when no platform implementation is wired.
 *
 * It always reports [BiometricResult.Unavailable], so an approval that requires
 * authentication is refused (`NotAuthenticated`) instead of silently allowed.
 */
public object FailClosedBiometricAuthenticator : BiometricAuthenticator {
    override suspend fun authenticate(reason: String): BiometricResult =
        BiometricResult.Unavailable
}
