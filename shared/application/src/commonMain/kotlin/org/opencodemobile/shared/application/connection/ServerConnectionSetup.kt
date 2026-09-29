package org.opencodemobile.shared.application.connection

import kotlin.coroutines.cancellation.CancellationException
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerAddressError
import org.opencodemobile.shared.domain.connection.ServerAddressParser
import org.opencodemobile.shared.domain.connection.ServerAddressResult
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityException
import org.opencodemobile.shared.domain.connection.ServerImport
import org.opencodemobile.shared.domain.connection.ServerImportLinkParser
import org.opencodemobile.shared.domain.connection.ServerImportSource
import org.opencodemobile.shared.domain.connection.ServerProfile

/** Where a [ServerSetupPlan]'s address came from. */
public enum class ServerSetupSource {
    /** Typed by the user on the manual-entry screen. */
    ManualEntry,

    /** Decoded from a QR code. */
    QrCode,

    /** Delivered by the OS as a deep link. */
    DeepLink,
}

/**
 * A server address that parsed successfully and is ready to be shown to the
 * user and, on confirmation, connected to.
 *
 * The plan is a value object: it holds no credential and performs no I/O. Any
 * transport-level policy (for example refusing plaintext HTTP to a public host)
 * is enforced by the [OpenCodeGateway] adapter and by the connection-policy
 * work tracked separately; this plan only carries what the review screen must
 * disclose.
 */
public data class ServerSetupPlan(
    public val profile: ServerProfile,
    public val source: ServerSetupSource,
    public val fingerprint: ServerFingerprint? = null,
    public val pairingCode: String? = null,
) {
    /** True when the profile has no TLS and must carry the persistent warning. */
    public val isPlaintext: Boolean
        get() = profile.isPlaintextHttp

    /** `host:port`, shown in full on the review screen. */
    public val authority: String
        get() = profile.authority
}

/** Result of turning raw input into a [ServerSetupPlan]. */
public sealed interface ServerSetupPlanning {
    /** The input produced a usable plan. */
    public data class Ready(public val plan: ServerSetupPlan) : ServerSetupPlanning

    /** Manual input was well-formed enough to parse but was rejected. */
    public data class Invalid(
        public val reason: ServerAddressError,
        public val detail: String? = null,
    ) : ServerSetupPlanning

    /**
     * An import link was unrecognised or malformed. Per the T8 design it is
     * discarded silently, so the UI shows nothing rather than an error.
     */
    public data object Discarded : ServerSetupPlanning
}

/**
 * Result of validating a [ServerSetupPlan] against the live server.
 *
 * The variants mirror the typed failures the gateway can raise
 * ([ServerIdentityException] and its subclasses) plus the generic handshake
 * failure, so the UI has exactly one decision surface per outcome.
 */
public sealed interface ConnectionValidation {
    /** The handshake completed; [handshake] carries health and identity. */
    public data class Connected(public val handshake: ConnectionHandshake) : ConnectionValidation

    /** First contact: the presented [presented] identity must be confirmed by the user. */
    public data class IdentityConfirmationRequired(
        public val presented: ServerFingerprint,
    ) : ConnectionValidation

    /** The pinned identity changed; the user must explicitly re-confirm. */
    public data class IdentityChanged(
        public val previous: ServerFingerprint,
        public val presented: ServerFingerprint,
    ) : ConnectionValidation

    /** TLS completed without a leaf certificate, so no identity could be pinned. */
    public data object IdentityUnavailable : ConnectionValidation

    /** Any other handshake failure (unreachable, unhealthy, incomplete, incompatible). */
    public data class HandshakeFailed(public val message: String) : ConnectionValidation
}

/**
 * Cross-cutting use case behind the connection surfaces: it turns raw manual
 * input or an import link into a reviewable [ServerSetupPlan], and performs the
 * connection validation by delegating to [OpenCodeGateway]
 * (`docs/ARCHITECTURE.md` §3.1, §"Server profile import").
 *
 * It owns no UI, storage, or transport: the credential is passed in by the
 * composition root (read from the platform secure store), and the identity gate
 * that decides whether that credential may be released stays in the adapter, so
 * a caller that skips this class cannot bypass it.
 */
public class ServerConnectionSetup(
    private val gateway: OpenCodeGateway,
) {
    /** Plans a manual entry. [label] is the optional display name the user typed. */
    public fun planManualEntry(input: String, label: String? = null): ServerSetupPlanning {
        return when (val parsed = ServerAddressParser.parse(input, label)) {
            is ServerAddressResult.Valid -> ServerSetupPlanning.Ready(
                ServerSetupPlan(profile = parsed.profile, source = ServerSetupSource.ManualEntry),
            )

            is ServerAddressResult.Invalid -> ServerSetupPlanning.Invalid(parsed.reason, parsed.detail)
        }
    }

    /**
     * Plans an import link. Returns [ServerSetupPlanning.Discarded] for anything
     * unrecognised or malformed.
     */
    public fun planImport(
        raw: String,
        source: ServerSetupSource = ServerSetupSource.DeepLink,
        verifiedImportHosts: Set<String> = emptySet(),
    ): ServerSetupPlanning {
        val importSource = when (source) {
            ServerSetupSource.QrCode -> ServerImportSource.QrCode
            else -> ServerImportSource.DeepLink
        }
        val imported: ServerImport = ServerImportLinkParser.parseOrNull(
            raw = raw,
            source = importSource,
            verifiedImportHosts = verifiedImportHosts,
        ) ?: return ServerSetupPlanning.Discarded

        return ServerSetupPlanning.Ready(
            ServerSetupPlan(
                profile = imported.profile,
                source = source,
                fingerprint = imported.fingerprint,
                pairingCode = imported.pairingCode,
            ),
        )
    }

    /**
     * Validates [plan] against the live server, returning a typed outcome. The
     * credential is never attached after a failed identity check, because that
     * enforcement lives in the adapter.
     */
    public suspend fun validate(
        plan: ServerSetupPlan,
        credential: ServerCredential?,
    ): ConnectionValidation {
        return try {
            ConnectionValidation.Connected(gateway.connect(plan.profile, credential))
        } catch (failure: ServerIdentityException.ConfirmationRequired) {
            ConnectionValidation.IdentityConfirmationRequired(failure.presented)
        } catch (failure: ServerIdentityException.IdentityChanged) {
            ConnectionValidation.IdentityChanged(failure.previous, failure.presented)
        } catch (failure: ServerIdentityException.NoCertificatePresented) {
            ConnectionValidation.IdentityUnavailable
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            ConnectionValidation.HandshakeFailed(failure.message ?: "The connection handshake failed")
        }
    }
}
