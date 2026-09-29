package org.opencodemobile.features.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opencodemobile.shared.domain.permission.PermissionDecision

/**
 * The sticky permission banner (V1-06, `docs/DESIGN-SYSTEM.md` §"PermissionBanner").
 *
 * It is rendered exactly while a request is pending and it is **not
 * dismissable**: there is no dismiss callback, no swipe-to-dismiss wrapper, and
 * no close affordance. The only way to make it go away is to submit one of the
 * decisions the server exposed, which removes the request from the pending set.
 *
 * The full command/targets are shown verbatim in a wrapping monospace block; they
 * are never truncated or summarized.
 */
@Composable
public fun PermissionBanner(
    model: PermissionBannerModel,
    onDecision: (PermissionDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "[!] Permission required · ${model.tool}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (model.targets.isNotEmpty()) {
                Text(
                    text = model.targets.joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (model.argumentsText.isNotBlank()) {
                Text(
                    text = model.argumentsText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (decision in model.decisions) {
                    PermissionDecisionButton(decision = decision, onDecision = onDecision)
                }
            }
        }
    }
}

/**
 * One decision control. No control is pre-selected or auto-focused, and the
 * dangerous direction is visually distinct (`docs/DESIGN-SYSTEM.md`).
 */
@Composable
private fun PermissionDecisionButton(
    decision: PermissionDecisionUi,
    onDecision: (PermissionDecision) -> Unit,
) {
    when (decision.emphasis) {
        PermissionEmphasis.Primary -> Button(onClick = { onDecision(decision.decision) }) {
            Text(decision.label)
        }

        PermissionEmphasis.Ghost -> OutlinedButton(onClick = { onDecision(decision.decision) }) {
            Text(decision.label)
        }

        PermissionEmphasis.Danger -> Button(
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
