package org.opencodemobile.features.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.shared.application.chat.ComposerState

/**
 * The V1-05 prompt composer: a monospace input and one explicit Send action.
 *
 * It is stateless: it renders [ComposerState] and forwards intents. There is no
 * auto-send path, no send-on-recomposition, and no send-on-restore — the only
 * call to `send()` is the user's tap. `docs/DESIGN-SYSTEM.md`: monospace, no
 * social bubbles, technical tone.
 */
@Composable
public fun ComposerBar(
    state: ComposerState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenCodeColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.bgPanel,
        contentColor = colors.text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenCodeSpacing.x3),
            verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !state.sending,
                textStyle = OpenCodeType.body,
                placeholder = {
                    Text("Send a prompt", style = OpenCodeType.body, color = colors.textMuted)
                },
                maxLines = 6,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val error = state.error
                if (error != null) {
                    Text(
                        text = error,
                        style = OpenCodeType.meta,
                        color = colors.danger,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Button(
                    onClick = onSend,
                    // D8: the mutation affordance is disabled offline, not merely refused.
                    enabled = enabled && state.canSend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                ) {
                    Text("Send", style = OpenCodeType.control)
                }
            }
        }
    }
}
