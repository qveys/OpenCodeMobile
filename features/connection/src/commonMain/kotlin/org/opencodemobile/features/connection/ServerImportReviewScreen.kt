package org.opencodemobile.features.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The single review screen every import source (manual entry, QR, deep link)
 * funnels through (`docs/ARCHITECTURE.md` §"Mandatory review before persisting").
 *
 * It enforces the T8 rules in the UI:
 * - full target disclosure — host and port are shown in full, never truncated;
 * - explicit, distinct confirmation — persisting requires the "Add server" tap;
 * - no silent overwrite — a matching stored profile switches to the
 *   "Update existing server?" comparison;
 * - the plaintext-HTTP warning is persistent while such a profile is reviewed.
 *
 * First contact is handled here too: when [ConnectionSetupUiState.identityPrompt]
 * is set, the fingerprint the server presented is shown and must be confirmed
 * before the credential is released.
 */
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

    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (updating) "Update existing server?" else "Add server",
            style = MaterialTheme.typography.headlineSmall,
        )

        // Full target disclosure: nothing elided.
        ReviewRow("Host", profile.host)
        ReviewRow("Port", profile.port.toString())
        ReviewRow("Transport", if (profile.isPlaintextHttp) "Plaintext HTTP (no TLS)" else "HTTPS")
        plan.fingerprint?.let { fingerprint ->
            ReviewRow("Fingerprint", fingerprint.colonSeparated)
        }
        ReviewRow("Source", plan.source.displayName())

        if (plan.isPlaintext) {
            Text(
                text = "Warning: this server uses plaintext HTTP. Traffic and credentials are readable on the network.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (updating) {
            HorizontalDivider()
            Text(text = "A server with this address is already stored.", style = MaterialTheme.typography.bodyMedium)
            val existing = state.existingProfile
            if (existing != null) {
                ReviewRow("Current label", existing.label ?: "(none)")
                ReviewRow("Current address", existing.authority)
                state.existingFingerprint?.let { pinned ->
                    ReviewRow("Current fingerprint", pinned.colonSeparated)
                }
            }
        }

        state.identityPrompt?.let { presented ->
            HorizontalDivider()
            Text(
                text = "First contact with this server. Confirm its identity before connecting:",
                style = MaterialTheme.typography.bodyMedium,
            )
            ReviewRow("Presented fingerprint", presented.colonSeparated)
        }

        state.failure?.let { failure ->
            Text(text = failure, color = MaterialTheme.colorScheme.error)
        }

        if (state.identityPrompt != null) {
            Button(
                onClick = onConfirmIdentity,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            ) {
                Text("Trust this server")
            }
        } else {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            ) {
                Text(if (updating) "Update server" else "Add server")
            }
        }

        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
        ) {
            Text("Cancel")
        }

        if (state.busy) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
    )
}
