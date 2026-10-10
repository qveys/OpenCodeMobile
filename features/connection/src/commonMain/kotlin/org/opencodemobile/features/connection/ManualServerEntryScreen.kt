package org.opencodemobile.features.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.opencodemobile.design.system.OpenCodeSpace
import org.opencodemobile.design.system.OpenCodeType
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.connection_manual_error
import org.opencodemobile.design.system.resources.connection_manual_address_label
import org.opencodemobile.design.system.resources.connection_manual_address_placeholder
import org.opencodemobile.design.system.resources.connection_manual_connect
import org.opencodemobile.design.system.resources.connection_manual_label
import org.opencodemobile.design.system.resources.connection_manual_scan_failed
import org.opencodemobile.design.system.resources.connection_manual_scan_qr

/**
 * Manual server address entry (`docs/ARCHITECTURE.md` §"Server profile import"
 * path 1). Stateless: the caller owns [ConnectionSetupUiState] and reacts to the
 * callbacks.
 *
 * @param onScanRequest when non-null, a "Scan QR code" action is offered. It is
 *   null on platforms where no camera scanner is wired, so the screen degrades
 *   to manual entry rather than offering a dead button.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
public fun ManualServerEntryScreen(
    state: ConnectionSetupUiState,
    onAddressChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(OpenCodeSpace.space4),
        verticalArrangement = Arrangement.spacedBy(OpenCodeSpace.space3),
    ) {
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.connection_manual_address_label), style = OpenCodeType.meta) },
            placeholder = { Text(stringResource(Res.string.connection_manual_address_placeholder), style = OpenCodeType.tech) },
            singleLine = true,
            isError = state.manualError != null,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )

        OutlinedTextField(
            value = state.label,
            onValueChange = onLabelChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.connection_manual_label), style = OpenCodeType.meta) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        state.manualError?.let {
            Text(text = stringResource(Res.string.connection_manual_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = OpenCodeType.body)
        }
        if (state.scanFailed) {
            Text(
                text = stringResource(Res.string.connection_manual_scan_failed),
                color = MaterialTheme.colorScheme.error,
                style = OpenCodeType.body,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        state.failure?.let { failure ->
            Text(text = failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = OpenCodeType.body)
        }

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().heightIn(min = OpenCodeSpace.hitAndroid),
            enabled = !state.busy,
        ) {
            Text(stringResource(Res.string.connection_manual_connect), style = OpenCodeType.control)
        }

        if (onScanRequest != null) {
            OutlinedButton(
                onClick = onScanRequest,
                modifier = Modifier.fillMaxWidth().heightIn(min = OpenCodeSpace.hitAndroid),
                enabled = !state.busy,
            ) {
                Text(stringResource(Res.string.connection_manual_scan_qr), style = OpenCodeType.control)
            }
        }

        if (state.busy) {
            Spacer(Modifier.height(OpenCodeSpace.space1))
            CircularProgressIndicator()
        }
    }
}
