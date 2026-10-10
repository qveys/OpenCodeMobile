package org.opencodemobile.features.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeSpace
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.connection_add_server
import org.opencodemobile.design.system.resources.connection_cancel
import org.opencodemobile.design.system.resources.connection_current_address
import org.opencodemobile.design.system.resources.connection_current_fingerprint
import org.opencodemobile.design.system.resources.connection_current_label
import org.opencodemobile.design.system.resources.connection_duplicate_server
import org.opencodemobile.design.system.resources.connection_fingerprint
import org.opencodemobile.design.system.resources.connection_first_contact
import org.opencodemobile.design.system.resources.connection_host
import org.opencodemobile.design.system.resources.connection_http_warning
import org.opencodemobile.design.system.resources.connection_no_label
import org.opencodemobile.design.system.resources.connection_port
import org.opencodemobile.design.system.resources.connection_presented_fingerprint
import org.opencodemobile.design.system.resources.connection_review_add_title
import org.opencodemobile.design.system.resources.connection_review_update_title
import org.opencodemobile.design.system.resources.connection_source
import org.opencodemobile.design.system.resources.connection_source_manual
import org.opencodemobile.design.system.resources.connection_source_qr
import org.opencodemobile.design.system.resources.connection_source_deep_link
import org.opencodemobile.design.system.resources.connection_transport
import org.opencodemobile.design.system.resources.connection_transport_http
import org.opencodemobile.design.system.resources.connection_transport_https
import org.opencodemobile.design.system.resources.connection_trust_server
import org.opencodemobile.design.system.resources.connection_update_server

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
public fun ServerImportReviewScreen(
    state: ConnectionSetupUiState,
    onConfirm: () -> Unit,
    onConfirmIdentity: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = state.review ?: return
    val profile = plan.profile
    val updating = state.updatesExistingProfile
    val colors = LocalOpenCodeColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(OpenCodeSpace.space4),
        verticalArrangement = Arrangement.spacedBy(OpenCodeSpace.space3),
    ) {
        Text(
            text = stringResource(if (updating) Res.string.connection_review_update_title else Res.string.connection_review_add_title),
            style = OpenCodeType.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Column(verticalArrangement = Arrangement.spacedBy(OpenCodeSpace.space2)) {
            ReviewRow(stringResource(Res.string.connection_host), profile.host)
            ReviewRow(stringResource(Res.string.connection_port), profile.port.toString())
            ReviewRow(
                stringResource(Res.string.connection_transport),
                stringResource(if (profile.isPlaintextHttp) Res.string.connection_transport_http else Res.string.connection_transport_https),
            )
            plan.fingerprint?.let { ReviewRow(stringResource(Res.string.connection_fingerprint), it.colonSeparated) }
            ReviewRow(stringResource(Res.string.connection_source), stringResource(when (plan.source) {
                org.opencodemobile.shared.application.connection.ServerSetupSource.ManualEntry -> Res.string.connection_source_manual
                org.opencodemobile.shared.application.connection.ServerSetupSource.QrCode -> Res.string.connection_source_qr
                org.opencodemobile.shared.application.connection.ServerSetupSource.DeepLink -> Res.string.connection_source_deep_link
            }))
        }
        if (plan.isPlaintext) {
            Text(stringResource(Res.string.connection_http_warning), color = colors.warning, style = OpenCodeType.body)
        }
        if (updating) {
            HorizontalDivider()
            Text(stringResource(Res.string.connection_duplicate_server), style = OpenCodeType.body)
            state.existingProfile?.let { existing ->
                ReviewRow(stringResource(Res.string.connection_current_label), existing.label ?: stringResource(Res.string.connection_no_label))
                ReviewRow(stringResource(Res.string.connection_current_address), existing.authority)
                state.existingFingerprint?.let { ReviewRow(stringResource(Res.string.connection_current_fingerprint), it.colonSeparated) }
            }
        }
        state.identityPrompt?.let { presented ->
            HorizontalDivider()
            Text(stringResource(Res.string.connection_first_contact), style = OpenCodeType.body)
            ReviewRow(stringResource(Res.string.connection_presented_fingerprint), presented.colonSeparated)
        }
        state.failure?.let { failure ->
            Text(
                failure,
                color = colors.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                style = OpenCodeType.body,
            )
        }
        Button(onClick = if (state.identityPrompt != null) onConfirmIdentity else onConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = OpenCodeSpace.hitAndroid), enabled = !state.busy) {
            Text(stringResource(if (state.identityPrompt != null) Res.string.connection_trust_server else if (updating) Res.string.connection_update_server else Res.string.connection_add_server), style = OpenCodeType.control)
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = OpenCodeSpace.hitAndroid), enabled = !state.busy) {
            Text(stringResource(Res.string.connection_cancel), style = OpenCodeType.control)
        }
        if (state.busy) CircularProgressIndicator()
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenCodeSpace.space3),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = OpenCodeType.bodyStrong)
        Text(value, modifier = Modifier.weight(2f), style = OpenCodeType.tech)
    }
}
