package org.opencodemobile.shared.security.identity

import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityCheck
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerIdentityVerifier
import org.opencodemobile.shared.domain.connection.ServerProfile

/**
 * Result of gating the credential on server identity.
 *
 * [Authorized] is the only state that releases the credential to the
 * transport. [ConfirmationRequired] and [Blocked] fail closed: no request
 * carrying the credential may be sent in either state.
 */
public sealed interface ServerIdentityAuthorization {
    /**
     * The credential may be attached. [plaintextWarning] is non-null only for a
     * plaintext-HTTP profile, where the server identity cannot be verified at
     * all; callers must surface it as visible text (not an icon) on every use.
     */
    public data class Authorized(
        public val fingerprint: ServerFingerprint?,
        public val plaintextWarning: String? = null,
    ) : ServerIdentityAuthorization

    /** First contact: [presented] must be displayed and explicitly confirmed first. */
    public data class ConfirmationRequired(
        public val presented: ServerFingerprint,
    ) : ServerIdentityAuthorization

    /** Mismatch against the pin: blocking, no retry/fallback until re-confirmed. */
    public data class Blocked(
        public val previous: ServerFingerprint,
        public val presented: ServerFingerprint,
    ) : ServerIdentityAuthorization
}

/**
 * Trust-on-first-use coordinator for server identity
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)").
 *
 * It owns the decision logic only: [ServerIdentityStore] persists the pin and
 * [ServerIdentityVerifier] observes the presented fingerprint. It never sends
 * the credential itself — [ServerIdentityGate] does the fail-closed gating.
 */
public class TofuServerIdentityCoordinator(
    private val store: ServerIdentityStore,
    private val verifier: ServerIdentityVerifier,
) {
    /**
     * Observes the server's presented identity and compares it to the pin.
     * This is the read-only half: it never persists anything.
     */
    public suspend fun inspect(profile: ServerProfile): ServerIdentityCheck {
        if (profile.isPlaintextHttp) return ServerIdentityCheck.PlaintextHttp

        val presented = verifier.presentedFingerprint(profile)
        val pinned = store.pinnedFingerprint(profile.id)
        return when {
            pinned == null -> ServerIdentityCheck.FirstContact(presented)
            pinned == presented -> ServerIdentityCheck.Trusted(pinned)
            else -> ServerIdentityCheck.Changed(previous = pinned, presented = presented)
        }
    }

    /**
     * Persists [presented] after the user explicitly confirmed a first contact.
     *
     * @throws ServerIdentityException.IdentityChanged when a pin already exists
     *   and differs — the first-contact path cannot overwrite an existing pin.
     */
    public suspend fun confirmFirstContact(
        profile: ServerProfile,
        presented: ServerFingerprint,
    ): ServerIdentityCheck.Trusted {
        val existing = store.pinnedFingerprint(profile.id)
        if (existing != null && existing != presented) {
            throw ServerIdentityException.IdentityChanged(previous = existing, presented = presented)
        }
        store.storePinnedFingerprint(profile.id, presented)
        return ServerIdentityCheck.Trusted(presented)
    }

    /**
     * Replaces the pinned identity after an explicit review of a changed
     * fingerprint (a fresh TOFU acceptance). This is the only transition out of
     * [ServerIdentityCheck.Changed], and it must always be driven by a distinct
     * user confirmation — never by a retry.
     */
    public suspend fun acceptChangedIdentity(
        profile: ServerProfile,
        presented: ServerFingerprint,
    ): ServerIdentityCheck.Trusted {
        if (profile.isPlaintextHttp) {
            throw IllegalStateException("A plaintext HTTP profile has no certificate to pin")
        }
        store.storePinnedFingerprint(profile.id, presented)
        return ServerIdentityCheck.Trusted(presented)
    }

    /** Forgets the pin for [profile], e.g. when the user removes the profile. */
    public suspend fun forget(profile: ServerProfile) {
        store.clearPinnedFingerprint(profile.id)
    }
}

/**
 * Fail-closed credential gate in front of the transport.
 *
 * The adapter must call [authorize] on every connect and refuse to attach the
 * `Authorization` header unless the result is [ServerIdentityAuthorization.Authorized].
 */
public class ServerIdentityGate(
    private val coordinator: TofuServerIdentityCoordinator,
) {
    public suspend fun authorize(profile: ServerProfile): ServerIdentityAuthorization =
        when (val check = coordinator.inspect(profile)) {
            is ServerIdentityCheck.Trusted ->
                ServerIdentityAuthorization.Authorized(check.pinned)

            ServerIdentityCheck.PlaintextHttp ->
                ServerIdentityAuthorization.Authorized(
                    fingerprint = null,
                    plaintextWarning = PlaintextHttpWarning.TEXT,
                )

            is ServerIdentityCheck.FirstContact ->
                ServerIdentityAuthorization.ConfirmationRequired(check.presented)

            is ServerIdentityCheck.Changed ->
                ServerIdentityAuthorization.Blocked(
                    previous = check.previous,
                    presented = check.presented,
                )
        }
}

/**
 * Persistent textual warning shown for every plaintext-HTTP profile
 * (`docs/ARCHITECTURE.md` §"Server identity verification (T1)"). Deliberately
 * plain-language and never reduced to a padlock icon.
 */
public object PlaintextHttpWarning {
    public const val TEXT: String =
        "This server uses plaintext HTTP. Anyone on the network path can read your " +
            "access credential and all traffic. Use HTTPS whenever possible."
}