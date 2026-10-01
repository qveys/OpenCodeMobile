package org.opencodemobile.features.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp

/**
 * Manual server address entry (`docs/ARCHITECTURE.md` §"Server profile import"
 * path 1). Stateless: the caller owns [ConnectionSetupUiState] and reacts to the
 * callbacks.
 *
 * @param onScanRequest when non-null, a "Scan QR code" action is offered. It is
 *   null on platforms where no camera scanner is wired, so the screen degrades
 *   to manual entry rather than offering a dead button.
 */
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
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Connect to a server", style = MaterialTheme.typography.headlineSmall)
        AddressField(state, onAddressChange)
        LabelField(state, onLabelChange)
        ManualEntryMessages(state)
        ManualEntryActions(state, onSubmit, onScanRequest)
        if (state.busy) {
            Spacer(Modifier.height(4.dp))
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun AddressField(state: ConnectionSetupUiState, onAddressChange: (String) -> Unit) {
    OutlinedTextField(
        value = state.address,
        onValueChange = onAddressChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Server address") },
        placeholder = { Text("192.168.1.10:4096 or https://host") },
        singleLine = true,
        isError = state.manualError != null,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
    )
}

@Composable
private fun LabelField(state: ConnectionSetupUiState, onLabelChange: (String) -> Unit) {
    OutlinedTextField(
        value = state.label,
        onValueChange = onLabelChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Label (optional)") },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
}

@Composable
private fun ManualEntryMessages(state: ConnectionSetupUiState) {
    state.manualError?.let { error ->
        Text(text = error.message(), color = MaterialTheme.colorScheme.error)
    }
    if (state.scanFailed) {
        Text(
            text = "That QR code is not an OpenCode Mobile import link.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    state.failure?.let { failure ->
        Text(text = failure, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ManualEntryActions(
    state: ConnectionSetupUiState,
    onSubmit: () -> Unit,
    onScanRequest: (() -> Unit)?,
) {
    Button(
        onClick = onSubmit,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy,
    ) {
        Text("Connect")
    }

    if (onScanRequest != null) {
        OutlinedButton(
            onClick = onScanRequest,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
        ) {
            Text("Scan QR code")
        }
    }
}
