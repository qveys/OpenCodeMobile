package org.opencodemobile.features.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.connection_address_blank
import org.opencodemobile.design.system.resources.connection_address_credentials_not_allowed
import org.opencodemobile.design.system.resources.connection_address_invalid_host
import org.opencodemobile.design.system.resources.connection_address_invalid_port
import org.opencodemobile.design.system.resources.connection_address_missing_host
import org.opencodemobile.design.system.resources.connection_address_path_not_allowed
import org.opencodemobile.design.system.resources.connection_address_port_out_of_range
import org.opencodemobile.design.system.resources.connection_address_unknown_scheme
import org.opencodemobile.design.system.resources.connection_failure_address_hint
import org.opencodemobile.design.system.resources.connection_failure_address_message
import org.opencodemobile.design.system.resources.connection_failure_address_title
import org.opencodemobile.design.system.resources.connection_failure_auth_hint
import org.opencodemobile.design.system.resources.connection_failure_auth_message
import org.opencodemobile.design.system.resources.connection_failure_auth_title
import org.opencodemobile.design.system.resources.connection_failure_changed_hint
import org.opencodemobile.design.system.resources.connection_failure_changed_message
import org.opencodemobile.design.system.resources.connection_failure_changed_title
import org.opencodemobile.design.system.resources.connection_failure_handshake_hint
import org.opencodemobile.design.system.resources.connection_failure_handshake_message
import org.opencodemobile.design.system.resources.connection_failure_handshake_title
import org.opencodemobile.design.system.resources.connection_failure_incompatible_hint
import org.opencodemobile.design.system.resources.connection_failure_incompatible_message
import org.opencodemobile.design.system.resources.connection_failure_incompatible_title
import org.opencodemobile.design.system.resources.connection_failure_link_hint
import org.opencodemobile.design.system.resources.connection_failure_link_message
import org.opencodemobile.design.system.resources.connection_failure_link_title
import org.opencodemobile.design.system.resources.connection_failure_method_hint
import org.opencodemobile.design.system.resources.connection_failure_method_message
import org.opencodemobile.design.system.resources.connection_failure_method_title
import org.opencodemobile.design.system.resources.connection_failure_policy_hint
import org.opencodemobile.design.system.resources.connection_failure_policy_message
import org.opencodemobile.design.system.resources.connection_failure_policy_title
import org.opencodemobile.design.system.resources.connection_failure_rejected_hint
import org.opencodemobile.design.system.resources.connection_failure_rejected_message
import org.opencodemobile.design.system.resources.connection_failure_rejected_title
import org.opencodemobile.design.system.resources.connection_failure_storage_hint
import org.opencodemobile.design.system.resources.connection_failure_storage_message
import org.opencodemobile.design.system.resources.connection_failure_storage_title
import org.opencodemobile.design.system.resources.connection_failure_unconfirmed_hint
import org.opencodemobile.design.system.resources.connection_failure_unconfirmed_message
import org.opencodemobile.design.system.resources.connection_failure_unconfirmed_title
import org.opencodemobile.design.system.resources.connection_failure_unhealthy_hint
import org.opencodemobile.design.system.resources.connection_failure_unhealthy_message
import org.opencodemobile.design.system.resources.connection_failure_unhealthy_title
import org.opencodemobile.design.system.resources.connection_failure_unknown_hint
import org.opencodemobile.design.system.resources.connection_failure_unknown_message
import org.opencodemobile.design.system.resources.connection_failure_unknown_title
import org.opencodemobile.design.system.resources.connection_failure_unreachable_hint
import org.opencodemobile.design.system.resources.connection_failure_unreachable_message
import org.opencodemobile.design.system.resources.connection_failure_unreachable_title
import org.opencodemobile.design.system.resources.connection_failure_unverifiable_hint
import org.opencodemobile.design.system.resources.connection_failure_unverifiable_message
import org.opencodemobile.design.system.resources.connection_failure_unverifiable_title
import org.opencodemobile.design.system.resources.connection_manual_error
import org.opencodemobile.design.system.resources.connection_pinned_fingerprint
import org.opencodemobile.design.system.resources.connection_presented_fingerprint
import org.opencodemobile.shared.domain.connection.ServerInputProblem

internal fun ServerInputProblem?.messageRes(): StringResource? = when (this) {
    ServerInputProblem.BLANK -> Res.string.connection_address_blank
    ServerInputProblem.MISSING_HOST -> Res.string.connection_address_missing_host
    ServerInputProblem.INVALID_HOST -> Res.string.connection_address_invalid_host
    ServerInputProblem.INVALID_PORT -> Res.string.connection_address_invalid_port
    ServerInputProblem.PORT_OUT_OF_RANGE -> Res.string.connection_address_port_out_of_range
    ServerInputProblem.UNKNOWN_SCHEME -> Res.string.connection_address_unknown_scheme
    ServerInputProblem.PATH_NOT_ALLOWED -> Res.string.connection_address_path_not_allowed
    ServerInputProblem.CREDENTIALS_NOT_ALLOWED -> Res.string.connection_address_credentials_not_allowed
    else -> null
}

/** Localised message for a rejected manual address; specific per problem. */
@Composable
internal fun manualErrorText(problem: ServerInputProblem): String =
    stringResource(problem.messageRes() ?: Res.string.connection_manual_error)

private fun FailureKind.resources(): Triple<StringResource, StringResource, StringResource> = when (this) {
    FailureKind.UNREACHABLE -> Triple(Res.string.connection_failure_unreachable_title, Res.string.connection_failure_unreachable_message, Res.string.connection_failure_unreachable_hint)
    FailureKind.UNHEALTHY -> Triple(Res.string.connection_failure_unhealthy_title, Res.string.connection_failure_unhealthy_message, Res.string.connection_failure_unhealthy_hint)
    FailureKind.HANDSHAKE -> Triple(Res.string.connection_failure_handshake_title, Res.string.connection_failure_handshake_message, Res.string.connection_failure_handshake_hint)
    FailureKind.INCOMPATIBLE -> Triple(Res.string.connection_failure_incompatible_title, Res.string.connection_failure_incompatible_message, Res.string.connection_failure_incompatible_hint)
    FailureKind.REJECTED -> Triple(Res.string.connection_failure_rejected_title, Res.string.connection_failure_rejected_message, Res.string.connection_failure_rejected_hint)
    FailureKind.AUTH -> Triple(Res.string.connection_failure_auth_title, Res.string.connection_failure_auth_message, Res.string.connection_failure_auth_hint)
    FailureKind.UNCONFIRMED -> Triple(Res.string.connection_failure_unconfirmed_title, Res.string.connection_failure_unconfirmed_message, Res.string.connection_failure_unconfirmed_hint)
    FailureKind.CHANGED -> Triple(Res.string.connection_failure_changed_title, Res.string.connection_failure_changed_message, Res.string.connection_failure_changed_hint)
    FailureKind.UNVERIFIABLE -> Triple(Res.string.connection_failure_unverifiable_title, Res.string.connection_failure_unverifiable_message, Res.string.connection_failure_unverifiable_hint)
    FailureKind.POLICY -> Triple(Res.string.connection_failure_policy_title, Res.string.connection_failure_policy_message, Res.string.connection_failure_policy_hint)
    FailureKind.METHOD -> Triple(Res.string.connection_failure_method_title, Res.string.connection_failure_method_message, Res.string.connection_failure_method_hint)
    FailureKind.ADDRESS -> Triple(Res.string.connection_failure_address_title, Res.string.connection_failure_address_message, Res.string.connection_failure_address_hint)
    FailureKind.LINK -> Triple(Res.string.connection_failure_link_title, Res.string.connection_failure_link_message, Res.string.connection_failure_link_hint)
    FailureKind.STORAGE -> Triple(Res.string.connection_failure_storage_title, Res.string.connection_failure_storage_message, Res.string.connection_failure_storage_hint)
    FailureKind.UNKNOWN -> Triple(Res.string.connection_failure_unknown_title, Res.string.connection_failure_unknown_message, Res.string.connection_failure_unknown_hint)
}

/** Renders a [ConnectionFailure] from local templates only; fingerprints in monospace. */
@Composable
internal fun ConnectionFailureView(failure: ConnectionFailure, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val (title, message, hint) = failure.kind.resources()
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
        Text(stringResource(title), color = color, style = OpenCodeType.bodyStrong)
        Text(stringResource(failure.problem.messageRes() ?: message), color = color, style = OpenCodeType.body)
        failure.pinned?.let {
            Text(stringResource(Res.string.connection_pinned_fingerprint), color = color, style = OpenCodeType.meta)
            Text(it, color = color, style = OpenCodeType.tech)
        }
        failure.presented?.let {
            Text(stringResource(Res.string.connection_presented_fingerprint), color = color, style = OpenCodeType.meta)
            Text(it, color = color, style = OpenCodeType.tech)
        }
        Text(stringResource(hint), color = color, style = OpenCodeType.body)
    }
}
