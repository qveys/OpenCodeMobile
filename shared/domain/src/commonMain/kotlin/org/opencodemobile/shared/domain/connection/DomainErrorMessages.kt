package org.opencodemobile.shared.domain.connection

/**
 * A ready-to-render description of a [DomainError].
 *
 * [titleKey] and [messageKey] are stable identifiers a localization layer maps
 * to translated strings (the FR/EN externalization is OPE-104). The [title],
 * [message], and [actionHint] fields are default English text so the domain
 * layer is usable in tests and non-localized contexts without depending on
 * Compose resources (`shared/domain` has no UI dependency).
 */
public data class DomainErrorPresentation(
    /** Stable localization key for the title, e.g. `"domain_error.compatibility.title"`. */
    public val titleKey: String,
    /** Stable localization key for the message. */
    public val messageKey: String,
    /** Default English title. */
    public val title: String,
    /** Default English message, with structured details already substituted. */
    public val message: String,
    /** Optional default English next-step hint, or null when none applies. */
    public val actionHint: String?,
    /** Whether retrying the same operation may succeed with no user change. */
    public val retryable: Boolean,
)

/**
 * Maps a [DomainError] to a user-comprehensible presentation
 * (`docs/ARCHITECTURE.md` §3.1: the connection feature renders a specific
 * screen per failure mode).
 *
 * Presentation is pure data: it never exposes internals that are not useful to
 * the user, and it never includes the credential or a raw stack trace. The
 * incompatible-server case includes the server version and the supported range,
 * as required by `docs/ARCHITECTURE.md` §4.4.
 */
public object DomainErrorMessages {

    /** Renders [error] as user-facing text. */
    public fun present(error: DomainError): DomainErrorPresentation = when (error) {
        is DomainError.Unreachable -> DomainErrorPresentation(
            titleKey = "domain_error.connection_unreachable.title",
            messageKey = "domain_error.connection_unreachable.message",
            title = "Server not reachable",
            message = "The app could not reach the server. Check that the server is running, " +
                "that the address and port are correct, and that this device is on the same " +
                "network or tailnet.",
            actionHint = "Check the server address and try again.",
            retryable = true,
        )

        is DomainError.ServerUnhealthy -> DomainErrorPresentation(
            titleKey = "domain_error.server_unhealthy.title",
            messageKey = "domain_error.server_unhealthy.message",
            title = "Server is unhealthy",
            message = "The server answered but reports that it is not healthy. Restart the " +
                "OpenCode Server and try again.",
            actionHint = "Restart the server and try again.",
            retryable = true,
        )

        is DomainError.HandshakeIncomplete -> DomainErrorPresentation(
            titleKey = "domain_error.handshake_incomplete.title",
            messageKey = "domain_error.handshake_incomplete.message",
            title = "Incomplete handshake",
            message = "The server did not send the information the app needs to connect " +
                "(${error.detail}). Make sure this is an OpenCode Server and try again.",
            actionHint = "Check that this is an OpenCode Server.",
            retryable = false,
        )

        is DomainError.ServerIncompatible -> DomainErrorPresentation(
            titleKey = "domain_error.server_incompatible.title",
            messageKey = "domain_error.server_incompatible.message",
            title = "Server version not supported",
            message = buildString {
                append("The server runs ")
                append(error.serverVersion?.let { "OpenCode Server $it" } ?: "an unknown OpenCode Server version")
                append(", but this app supports $error.supportedRange.")
            },
            actionHint = "Update OpenCode Server, or install an app version that matches it.",
            retryable = false,
        )

        is DomainError.CredentialRejected -> DomainErrorPresentation(
            titleKey = "domain_error.credential_rejected.title",
            messageKey = "domain_error.credential_rejected.message",
            title = "Credential rejected",
            message = "The server rejected the saved credential. Enter a valid credential for " +
                "this server.",
            actionHint = "Enter the credential again.",
            retryable = false,
        )

        is DomainError.AuthenticationRequired -> DomainErrorPresentation(
            titleKey = "domain_error.authentication_required.title",
            messageKey = "domain_error.authentication_required.message",
            title = "Credential required",
            message = "This server requires a credential. Enter the credential to connect.",
            actionHint = "Enter the credential.",
            retryable = false,
        )

        is DomainError.IdentityUnconfirmed -> DomainErrorPresentation(
            titleKey = "domain_error.identity_unconfirmed.title",
            messageKey = "domain_error.identity_unconfirmed.message",
            title = "Confirm the server identity",
            message = "This is the first connection to this server. Check that this fingerprint " +
                "belongs to your server before you continue:\n${error.presented.colonSeparated}",
            actionHint = "Confirm the fingerprint only if you trust this server.",
            retryable = false,
        )

        is DomainError.IdentityChanged -> DomainErrorPresentation(
            titleKey = "domain_error.identity_changed.title",
            messageKey = "domain_error.identity_changed.message",
            title = "Server identity changed",
            message = "The server identity changed. This can be a certificate rotation, or a " +
                "different server.\nPinned:       ${error.previous.colonSeparated}\n" +
                "Presented: ${error.presented.colonSeparated}",
            actionHint = "Do not continue unless you changed the server yourself.",
            retryable = false,
        )

        is DomainError.IdentityNotVerifiable -> DomainErrorPresentation(
            titleKey = "domain_error.identity_not_verifiable.title",
            messageKey = "domain_error.identity_not_verifiable.message",
            title = "Unverified connection",
            message = "This connection uses plain HTTP, so the app cannot verify the server " +
                "identity. Continue only on a network you trust.",
            actionHint = "Use HTTPS if the server supports it.",
            retryable = false,
        )

        is DomainError.PolicyRejected -> DomainErrorPresentation(
            titleKey = "domain_error.policy_rejected.title",
            messageKey = "domain_error.policy_rejected.message",
            title = "Connection refused by policy",
            message = "Plain HTTP is not allowed for this server address (" +
                "${error.scope}). Use HTTPS, or connect from the local network or a tailnet.",
            actionHint = "Use an HTTPS address, or connect from a local network or tailnet.",
            retryable = false,
        )

        is DomainError.PolicyMethodNotAllowed -> DomainErrorPresentation(
            titleKey = "domain_error.policy_method_not_allowed.title",
            messageKey = "domain_error.policy_method_not_allowed.message",
            title = "Connection refused by policy",
            message = "The app tried to use an HTTP method that the connection policy does not " +
                "allow (${error.method}).",
            actionHint = "Update the app and try again.",
            retryable = false,
        )

        is DomainError.InvalidServerAddress -> DomainErrorPresentation(
            titleKey = "domain_error.invalid_server_address.title",
            messageKey = "domain_error.invalid_server_address.${error.problem.name.lowercase()}",
            title = "Check the server address",
            message = addressProblemMessage(error.problem, error.field),
            actionHint = "Correct the field and try again.",
            retryable = false,
        )

        is DomainError.InvalidImportLink -> DomainErrorPresentation(
            titleKey = "domain_error.invalid_import_link.title",
            messageKey = "domain_error.invalid_import_link.${error.problem.name.lowercase()}",
            title = "Cannot use this link",
            message = importProblemMessage(error.problem),
            actionHint = "Ask the server owner for a new link or QR code.",
            retryable = false,
        )

        is DomainError.StorageFailure -> DomainErrorPresentation(
            titleKey = "domain_error.storage_failure.title",
            messageKey = "domain_error.storage_failure.message",
            title = "Secure storage error",
            message = "The app could not read or write its secure storage. Your saved server " +
                "and credential are unchanged. Restart the app and try again.",
            actionHint = "Restart the app and try again.",
            retryable = true,
        )

        is DomainError.Unknown -> DomainErrorPresentation(
            titleKey = "domain_error.unknown.title",
            messageKey = "domain_error.unknown.message",
            title = "Unexpected error",
            message = "An unexpected error occurred while connecting. Try again.",
            actionHint = "Try again.",
            retryable = true,
        )
    }

    private fun addressProblemMessage(
        problem: ServerInputProblem,
        field: ServerAddressField,
    ): String {
        val detail = when (problem) {
            ServerInputProblem.BLANK -> "Enter a server address."
            ServerInputProblem.MISSING_HOST -> "Enter the server host name or IP address."
            ServerInputProblem.INVALID_HOST -> "The host name or IP address is not valid."
            ServerInputProblem.INVALID_PORT -> "The port is not a number."
            ServerInputProblem.PORT_OUT_OF_RANGE -> "The port must be between 1 and 65535."
            ServerInputProblem.UNKNOWN_SCHEME -> "Use an address that starts with http:// or https://."
            ServerInputProblem.PATH_NOT_ALLOWED -> "Use only the host and port, with no path, query, or fragment."
            ServerInputProblem.CREDENTIALS_NOT_ALLOWED -> "Do not put a credential in the server address."
            else -> "The server address is not valid."
        }
        return "$detail (field: ${field.name.lowercase()})"
    }

    private fun importProblemMessage(problem: ServerInputProblem): String = when (problem) {
        ServerInputProblem.MISSING_HOST -> "The link does not contain a server host."
        ServerInputProblem.INVALID_HOST -> "The link contains an invalid server host."
        ServerInputProblem.INVALID_PORT, ServerInputProblem.PORT_OUT_OF_RANGE ->
            "The link contains an invalid port."

        ServerInputProblem.UNSUPPORTED_VERSION ->
            "This link was made by a newer version of the app. Update the app and try again."
        ServerInputProblem.FINGERPRINT_INVALID -> "The link contains an invalid TLS fingerprint."
        ServerInputProblem.CREDENTIALS_NOT_ALLOWED ->
            "The link contains a credential. Links never carry credentials; ask for a new link."
        ServerInputProblem.UNKNOWN_PARAMETER -> "The link contains an unknown parameter."
        ServerInputProblem.INVALID_TLS -> "The link contains an invalid transport value."
        ServerInputProblem.MALFORMED, ServerInputProblem.BLANK, ServerInputProblem.PATH_NOT_ALLOWED,
        ServerInputProblem.UNKNOWN_SCHEME,
        -> "The link is not a valid OpenCode Mobile server link."
    }
}
