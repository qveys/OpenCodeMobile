package org.opencodemobile.features.connection

import org.opencodemobile.shared.application.connection.ServerSetupPlan
import org.opencodemobile.shared.domain.connection.ConnectionHandshake
import org.opencodemobile.shared.domain.connection.ServerAddressError
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerProfile

/**
 * Everything the connection surfaces render, as one immutable value.
 *
 * The entry screen is driven by [address] / [label] / [manualError]; the import
 * review screen by [review] / [existingProfile]; the post-validation states by
 * [busy] / [identityPrompt] / [connected] / [failure].
 */
public data class ConnectionSetupUiState(
    public val address: String = "",
    public val label: String = "",
    public val manualError: ServerAddressError? = null,
    public val scanFailed: Boolean = false,
    public val review: ServerSetupPlan? = null,
    public val existingProfile: ServerProfile? = null,
    public val busy: Boolean = false,
    public val identityPrompt: ServerFingerprint? = null,
    public val connected: ConnectionHandshake? = null,
    public val failure: String? = null,
) {
    /** True while the import review screen should be shown. */
    public val isReviewing: Boolean
        get() = review != null

    /**
     * True when the reviewed target matches an already-stored profile, so the
     * review screen must present the explicit "update existing?" comparison
     * instead of a generic add (no silent overwrite, T8).
     */
    public val updatesExistingProfile: Boolean
        get() = review != null && existingProfile != null && existingProfile.id == review.profile.id
}

/** User-facing message for a rejected manual address. */
public fun ServerAddressError.message(): String = when (this) {
    ServerAddressError.Blank -> "Enter a server address."
    ServerAddressError.UnsupportedScheme -> "Only http:// and https:// addresses are supported."
    ServerAddressError.CredentialsInAddress -> "Remove the credentials from the address; they are entered separately."
    ServerAddressError.PathNotAllowed -> "Enter only a host and port, without a path."
    ServerAddressError.QueryNotAllowed -> "Enter only a host and port, without a query string."
    ServerAddressError.FragmentNotAllowed -> "Enter only a host and port, without a fragment."
    ServerAddressError.InvalidHost -> "That host is not valid."
    ServerAddressError.InvalidPort -> "The port must be a number between 1 and 65535."
}
