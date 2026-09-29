package org.opencodemobile.features.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opencodemobile.shared.domain.permission.PermissionDecision

/**
 * The foreground confirmation screen (V1-06, `docs/ARCHITECTURE.md`
 * §"Permission approval confirmation (foreground + authenticated)").
 *
 * This is the only surface from which an approval can be finalized. It shows the
 * complete command/diff verbatim, offers exactly the decisions the server
 * exposed, and keeps "Approve" visually and positionally distinct from "Deny".
 * It has no dismiss affordance: leaving it returns to the still-pending banner,
 * it never decides on the user's behalf.
 *
 * The biometric / device-credential check is platform code (`expect`/`actual`
 * biometrics API); the caller runs it once per approval and passes the result in
 * [authenticated] so the screen can explain why an approval is not yet available.
 */
@Composable
public fun PermissionConfirmationScreen(
    model: PermissionBannerModel,
    authenticated: Boolean,
    onDecision: (PermissionDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Permission required · ${model.tool}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "The server is waiting for your decision. This authorizes the tool to run " +
                    "on your development machine.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (model.targets.isNotEmpty()) {
                Text(
                    text = model.targets.joinToString("\n"),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (model.argumentsText.isNotBlank()) {
                Text(
                    text = model.argumentsText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (!authenticated) {
                Text(
                    text = "Confirming an approval needs your device authentication.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (decision in model.decisions) {
                    ConfirmationDecisionButton(decision = decision, onDecision = onDecision)
                }
            }
        }
    }
}

@Composable
private fun ConfirmationDecisionButton(
    decision: PermissionDecisionUi,
    onDecision: (PermissionDecision) -> Unit,
) {
    when (decision.emphasis) {
        PermissionEmphasis.Primary -> Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onDecision(decision.decision) },
        ) {
            Text(decision.label)
        }

        PermissionEmphasis.Ghost -> OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onDecision(decision.decision) },
        ) {
            Text(decision.label)
        }

        PermissionEmphasis.Danger -> Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onDecision(decision.decision) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(decision.label)
        }
    }
}
