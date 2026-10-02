package org.opencodemobile.shared.domain.connection

/**
 * Stable, machine-readable category of a [DomainError]. The connection feature
 * (and any future one) branches on the category when it needs to decide *what
 * kind of screen* to show, without matching on message strings.
 */
public enum class DomainErrorCategory {
    /** The server could not be reached or did not answer a health probe. */
    CONNECTION,

    /** The server answered but its version is not supported by this client. */
    COMPATIBILITY,

    /** The credential was rejected, or none was supplied where one is required. */
    AUTHENTICATION,

    /** The server's TLS identity could not be trusted (T1). */
    IDENTITY,

    /** The connection policy refused the profile before any request was built. */
    POLICY,

    /** User input could not be turned into a valid server address or import link. */
    VALIDATION,

    /** Secure storage failed or held a corrupted record (B2). */
    STORAGE,

    /** Anything not yet classified; a deliberate catch-all, never a silent success. */
    UNKNOWN,
}

/**
 * The structured error code carried by every [DomainError]
 * (`docs/ARCHITECTURE.md` §3.1, §4.3 "the adapter maps HTTP and transport
 * failures to typed domain errors").
 *
 * The `wireValue` is stable across releases: it is what tests, logs, analytics,
 * and the localization layer key on. User-facing text is derived separately by
 * [DomainErrorMessages], never from the code itself.
 */
public enum class DomainErrorCode {
    CONNECTION_UNREACHABLE,
    CONNECTION_SERVER_UNHEALTHY,
    CONNECTION_HANDSHAKE_INCOMPLETE,
    COMPATIBILITY_SERVER_INCOMPATIBLE,
    AUTHENTICATION_CREDENTIAL_REJECTED,
    AUTHENTICATION_REQUIRED,
    IDENTITY_UNCONFIRMED,
    IDENTITY_CHANGED,
    IDENTITY_NOT_VERIFIABLE,
    POLICY_PUBLIC_PLAINTEXT_HTTP_REJECTED,
    POLICY_METHOD_NOT_ALLOWED,
    VALIDATION_INVALID_SERVER_ADDRESS,
    VALIDATION_INVALID_IMPORT_LINK,
    STORAGE_FAILURE,
    UNKNOWN,
    ;

    /** The [DomainErrorCategory] this code belongs to. */
    public val category: DomainErrorCategory
        get() = when (this) {
            CONNECTION_UNREACHABLE,
            CONNECTION_SERVER_UNHEALTHY,
            CONNECTION_HANDSHAKE_INCOMPLETE,
            -> DomainErrorCategory.CONNECTION

            COMPATIBILITY_SERVER_INCOMPATIBLE -> DomainErrorCategory.COMPATIBILITY

            AUTHENTICATION_CREDENTIAL_REJECTED,
            AUTHENTICATION_REQUIRED,
            -> DomainErrorCategory.AUTHENTICATION

            IDENTITY_UNCONFIRMED,
            IDENTITY_CHANGED,
            IDENTITY_NOT_VERIFIABLE,
            -> DomainErrorCategory.IDENTITY

            POLICY_PUBLIC_PLAINTEXT_HTTP_REJECTED -> DomainErrorCategory.POLICY
            POLICY_METHOD_NOT_ALLOWED -> DomainErrorCategory.POLICY

            VALIDATION_INVALID_SERVER_ADDRESS,
            VALIDATION_INVALID_IMPORT_LINK,
            -> DomainErrorCategory.VALIDATION

            STORAGE_FAILURE -> DomainErrorCategory.STORAGE

            UNKNOWN -> DomainErrorCategory.UNKNOWN
        }

    /**
     * Stable dotted identifier for this code, e.g.
     * `"connection.unreachable"`. Not shown to users.
     */
    public val wireValue: String
        get() = name.lowercase().replace('_', '.')
}

/**
 * The domain error hierarchy for the connection story
 * (`docs/ARCHITECTURE.md` §3.1: "Failure modes are typed at the domain
 * boundary (`DomainError`: unreachable, incompatible server, untrusted
 * identity, rejected credential)").
 *
 * Layered failures raised deeper in the stack (`HandshakeException`,
 * `ConnectionPolicyException`, `ServerIdentityException`) are converted to one
 * of these by [toDomainError], so a presentational layer only ever handles
 * [DomainError] and can switch on [code].
 */
public sealed class DomainError(
    /** Stable machine-readable code; see [DomainErrorCode]. */
    public val code: DomainErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The category of [code]; convenient for coarse branching. */
    public val category: DomainErrorCategory
        get() = code.category

    /**
     * Whether retrying the identical operation may succeed with no user
     * change. A validation or policy error is never retryable; a transient
     * transport failure is.
     */
    public open val isRetryable: Boolean
        get() = false

    /** The server could not be reached at all (DNS, TCP, TLS, or timeout). */
    public class Unreachable(cause: Throwable?) : DomainError(
        code = DomainErrorCode.CONNECTION_UNREACHABLE,
        message = "The server could not be reached",
        cause = cause,
    ) {
        override val isRetryable: Boolean get() = true
    }

    /** The health probe answered, but reported the server as unhealthy. */
    public class ServerUnhealthy : DomainError(
        code = DomainErrorCode.CONNECTION_SERVER_UNHEALTHY,
        message = "The server reported that it is unhealthy",
    ) {
        override val isRetryable: Boolean get() = true
    }

    /** The health probe answered but the handshake could not be completed. */
    public class HandshakeIncomplete(public val detail: String) : DomainError(
        code = DomainErrorCode.CONNECTION_HANDSHAKE_INCOMPLETE,
        message = "The handshake with the server was incomplete: $detail",
    )

    /**
     * The server's version is outside the client's [CompatibilityProfile]
     * (`docs/ARCHITECTURE.md` §4.4). [serverVersion] is null when the server
     * did not report a parseable version.
     */
    public class ServerIncompatible(
        public val serverVersion: ServerVersion?,
        public val supportedRange: String,
    ) : DomainError(
        code = DomainErrorCode.COMPATIBILITY_SERVER_INCOMPATIBLE,
        message = "Unsupported server version ${serverVersion ?: "<unknown>"}; supported: $supportedRange",
    )

    /** The server rejected the supplied credential (HTTP 401/403). */
    public class CredentialRejected(public val detail: String? = null) : DomainError(
        code = DomainErrorCode.AUTHENTICATION_CREDENTIAL_REJECTED,
        message = "The server rejected the credential" + (detail?.let { ": $it" } ?: ""),
    )

    /** The server requires a credential and none was supplied. */
    public class AuthenticationRequired : DomainError(
        code = DomainErrorCode.AUTHENTICATION_REQUIRED,
        message = "The server requires a credential",
    )

    /** A first-contact fingerprint was never confirmed (T1). */
    public class IdentityUnconfirmed(public val presented: ServerFingerprint) : DomainError(
        code = DomainErrorCode.IDENTITY_UNCONFIRMED,
        message = "The server identity was not confirmed",
    )

    /** The presented identity differs from the pinned one (T1, fail closed). */
    public class IdentityChanged(
        public val previous: ServerFingerprint,
        public val presented: ServerFingerprint,
    ) : DomainError(
        code = DomainErrorCode.IDENTITY_CHANGED,
        message = "The server identity changed (pinned ${previous.colonSeparated}, " +
            "presented ${presented.colonSeparated})",
    )

    /** The profile has no TLS, so no identity can be verified and no pin exists (T1). */
    public class IdentityNotVerifiable : DomainError(
        code = DomainErrorCode.IDENTITY_NOT_VERIFIABLE,
        message = "The server connection is not protected by TLS, so its identity cannot be verified",
    )

    /** [HttpConnectionPolicy] refused the profile before any request was built. */
    public class PolicyRejected(
        public val scope: ServerNetworkScope,
        public val violation: HttpPolicyViolation,
    ) : DomainError(
        code = DomainErrorCode.POLICY_PUBLIC_PLAINTEXT_HTTP_REJECTED,
        message = "Connection policy rejected the profile: $violation (scope $scope)",
    )

    /** A request used an HTTP method outside the connection policy allowlist. */
    public class PolicyMethodNotAllowed(public val method: String) : DomainError(
        code = DomainErrorCode.POLICY_METHOD_NOT_ALLOWED,
        message = "HTTP method $method is not allowed by the connection policy",
    )

    /**
     * Manual server-address input could not be turned into a valid address.
     * [problem] and [field] say what to fix; [raw] is the original text.
     */
    public class InvalidServerAddress(
        public val problem: ServerInputProblem,
        public val raw: String,
        public val field: ServerAddressField = problem.defaultField,
    ) : DomainError(
        code = DomainErrorCode.VALIDATION_INVALID_SERVER_ADDRESS,
        message = "Invalid server address: $problem (field $field)",
    )

    /**
     * A deep link / QR payload could not be turned into a valid import
     * (`docs/ARCHITECTURE.md` §"Server profile import").
     */
    public class InvalidImportLink(
        public val problem: ServerInputProblem,
        public val raw: String,
    ) : DomainError(
        code = DomainErrorCode.VALIDATION_INVALID_IMPORT_LINK,
        message = "Invalid server import link: $problem",
    )

    /** Secure storage failed or returned a corrupted record (B2). */
    public class StorageFailure(public val detail: String, cause: Throwable? = null) : DomainError(
        code = DomainErrorCode.STORAGE_FAILURE,
        message = "Secure storage failure: $detail",
        cause = cause,
    ) {
        override val isRetryable: Boolean get() = true
    }

    /** A failure that has no typed mapping yet; carries the original cause. */
    public class Unknown(cause: Throwable?) : DomainError(
        code = DomainErrorCode.UNKNOWN,
        message = "An unexpected error occurred",
        cause = cause,
    ) {
        override val isRetryable: Boolean get() = true
    }
}

/** The field of a manual address that a validation problem applies to. */
public enum class ServerAddressField {
    HOST,
    PORT,
    SCHEME,
    PATH,
    USER_INFO,
    LINK,
}

/**
 * Structured reasons a server address or import link was rejected. Each maps to
 * one user-facing message in [DomainErrorMessages].
 */
public enum class ServerInputProblem {
    /** Empty or whitespace-only input. */
    BLANK,

    /** No host was present at all. */
    MISSING_HOST,

    /** The host contained characters that no host may contain. */
    INVALID_HOST,

    /** The port text was not a number. */
    INVALID_PORT,

    /** The port number was outside `1..65535`. */
    PORT_OUT_OF_RANGE,

    /** A scheme other than `http`/`https` was given. */
    UNKNOWN_SCHEME,

    /** The address carried a path other than `/`. */
    PATH_NOT_ALLOWED,

    /** The address carried user-info (`user:pass@`); credentials never go in an address or link (T8). */
    CREDENTIALS_NOT_ALLOWED,

    /** The link/address could not be parsed at all. */
    MALFORMED,

    /** The link declared a version newer than this app understands. */
    UNSUPPORTED_VERSION,

    /** The link's fingerprint was not a valid SHA-256 value. */
    FINGERPRINT_INVALID,

    /** The link carried a parameter this version does not define. */
    UNKNOWN_PARAMETER,

    /** The link's `tls` value was neither `http` nor `https`. */
    INVALID_TLS,
    ;

    /** The default address field this problem is attributed to. */
    public val defaultField: ServerAddressField
        get() = when (this) {
            MISSING_HOST, INVALID_HOST -> ServerAddressField.HOST
            INVALID_PORT, PORT_OUT_OF_RANGE -> ServerAddressField.PORT
            UNKNOWN_SCHEME -> ServerAddressField.SCHEME
            PATH_NOT_ALLOWED -> ServerAddressField.PATH
            CREDENTIALS_NOT_ALLOWED -> ServerAddressField.USER_INFO
            BLANK, MALFORMED, UNSUPPORTED_VERSION, FINGERPRINT_INVALID, UNKNOWN_PARAMETER, INVALID_TLS ->
                ServerAddressField.LINK
        }
}

/**
 * Converts any failure raised on the connection path into the single
 * [DomainError] type consumed by the presentation layer.
 *
 * The low-level `*Exception` families stay where they are raised (they carry
 * the precise security/handshake detail); this is the one-way bridge to the
 * stable, code-carrying hierarchy. Passing a [DomainError] returns it
 * unchanged.
 */
public fun Throwable.toDomainError(): DomainError = when (this) {
    is DomainError -> this
    is HandshakeException.HealthUnavailable -> DomainError.Unreachable(cause)
    is HandshakeException.ServerUnhealthy -> DomainError.ServerUnhealthy()
    is HandshakeException.Incomplete -> DomainError.HandshakeIncomplete(detail)
    is HandshakeException.Incompatible -> DomainError.ServerIncompatible(serverVersion, profile.supportedRange)
    is ConnectionPolicyException.Rejected -> DomainError.PolicyRejected(decision.scope, decision.violation)
    is ConnectionPolicyException.MethodNotAllowed -> DomainError.PolicyMethodNotAllowed(method)
    is ServerIdentityException.ConfirmationRequired -> DomainError.IdentityUnconfirmed(presented)
    is ServerIdentityException.IdentityChanged -> DomainError.IdentityChanged(previous, presented)
    is ServerIdentityException.NoCertificatePresented -> DomainError.IdentityNotVerifiable()
    else -> DomainError.Unknown(this)
}
