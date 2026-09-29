package org.opencodemobile.shared.application.connection

import kotlin.coroutines.cancellation.CancellationException
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerAddressParser
import org.opencodemobile.shared.domain.connection.ServerAddressResult
import org.opencodemobile.shared.domain.connection.ServerCredential
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerImportLink
import org.opencodemobile.shared.domain.connection.ServerImportResult
import org.opencodemobile.shared.domain.connection.ServerProfile
import org.opencodemobile.shared.domain.connection.toDomainError

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
 * user and, on confirmation, connected to
 * (`docs/ARCHITECTURE.md` §"Server profile import").
 *
 * The plan is a value object: it holds no credential and performs no I/O. Any
 * transport-level policy (for example refusing plaintext HTTP to a public host)
 * is enforced by the [OpenCodeGateway] adapter, not here; this plan only carries
 * what the review screen must disclose.
 */
public data class ServerSetupPlan(
    public val profile: ServerProfile,
    public val source: ServerSetupSource,
    public val fingerprint: ServerFingerprint? = null,
) {
    /** True when the profile has no TLS and must carry the persistent warning. */
    public val isPlaintext: Boolean
        get() = profile.isPlaintextHttp

    /** `host:port`, shown in full on the review screen, with IPv6 hosts bracketed. */
    public val authority: String
        get() = if (profile.host.contains(':')) "[${profile.host}]:${profile.port}" else profile.authority
}

/** Result of turning raw input into a [ServerSetupPlan]. */
public sealed interface ServerSetupPlanning {
    /** The input produced a usable plan. */
    public data class Ready(public val plan: ServerSetupPlan) : ServerSetupPlanning

    /** Manual input was rejected; [error] says what to fix. */
    public data class Invalid(public val error: DomainError.InvalidServerAddress) : ServerSetupPlanning

    /**
     * An import link was unrecognised or malformed. Per the T8 design it is
     * discarded silently, so the UI shows nothing rather than an error
     * (`docs/ARCHITECTURE.md` §"Unrecognized or malformed import links … are
     * discarded silently").
     */
    public data object Discarded : ServerSetupPlanning
}

/**
 * Result of validating a [ServerSetupPlan] against the live server.
 *
 * [Rejected] carries the stable, code-carrying [DomainError] hierarchy built by
 * [toDomainError], so the presentation layer never has to match on message
 * strings (OPE-136).
 */
public sealed interface ConnectionValidation {
    /** The handshake completed; [handshake] carries health, version, and identity. */
    public data class Connected(public val handshake: ConnectionHandshake) : ConnectionValidation

    /** The connection was refused before or during the handshake; [error] is typed. */
    public data class Rejected(public val error: DomainError) : ConnectionValidation
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
        return when (val parsed = ServerAddressParser.parse(input)) {
            is ServerAddressResult.Valid -> {
                val address = parsed.address.copy(label = label?.trim()?.takeIf { it.isNotEmpty() })
                ServerSetupPlanning.Ready(
                    ServerSetupPlan(profile = address.toProfile(), source = ServerSetupSource.ManualEntry),
                )
            }

            is ServerAddressResult.Invalid -> ServerSetupPlanning.Invalid(parsed.error)
        }
    }

    /**
     * Plans an import link, as carried by a QR code or a deep link. Returns
     * [ServerSetupPlanning.Discarded] for anything unrecognised or malformed.
     */
    public fun planImport(
        raw: String,
        source: ServerSetupSource = ServerSetupSource.DeepLink,
    ): ServerSetupPlanning {
        return when (val parsed = ServerImportLink.parse(raw)) {
            is ServerImportResult.Valid -> ServerSetupPlanning.Ready(
                ServerSetupPlan(
                    profile = parsed.profile.toProfile(),
                    source = source,
                    fingerprint = parsed.profile.fingerprint,
                ),
            )

            is ServerImportResult.Malformed -> ServerSetupPlanning.Discarded
        }
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
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            ConnectionValidation.Rejected(failure.toDomainError())
        }
    }
}
