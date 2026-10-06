package org.opencodemobile.features.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.composer_dictate
import org.opencodemobile.design.system.resources.composer_dictating
import org.opencodemobile.design.system.resources.composer_dictation_permission
import org.opencodemobile.design.system.resources.composer_dictation_unavailable
import org.opencodemobile.design.system.resources.composer_placeholder
import org.opencodemobile.design.system.resources.composer_send
import org.opencodemobile.design.system.resources.composer_send_failed
import org.opencodemobile.shared.application.composer.ComposerNotice
import org.opencodemobile.shared.application.composer.ComposerState
import org.opencodemobile.shared.domain.dictation.DictationAvailability
import org.opencodemobile.shared.domain.dictation.DictationUnavailableReason

/**
 * The V1-10 prompt composer with on-device dictation.
 *
 * Security-relevant UI contract (T5 / ADR `docs/adr/on-device-speech-to-text.md`):
 *
 * - The mic button is **disabled, never hidden**, when on-device recognition is
 *   unavailable, and a short message explains why. The text field is always
 *   usable, so typing remains the fallback.
 * - Dictation only fills the text field. Sending requires a **distinct tap** on
 *   the send button; ending a transcription never sends (§9.5).
 */
@Composable
public fun ComposerScreen(
    presenter: DictationComposerPresenter,
    localeTag: String,
    modifier: Modifier = Modifier,
) {
    OpenCodeTheme(context = OpenCodeContext.Session) {
        val state by presenter.state.collectAsState()
        val colors = LocalOpenCodeColors.current

        LaunchedEffect(localeTag) { presenter.onLocaleChanged(localeTag) }

        Surface(
            modifier = modifier.fillMaxSize(),
            color = colors.bg,
            contentColor = colors.text,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(OpenCodeSpacing.x4),
                verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2),
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = presenter::onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    textStyle = OpenCodeType.body,
                    placeholder = {
                        Text(
                            text = stringResource(Res.string.composer_placeholder),
                            style = OpenCodeType.body,
                            color = colors.textMuted,
                        )
                    },
                )
                ComposerControls(
                    state = state,
                    onToggleDictation = presenter::toggleDictation,
                    onSend = presenter::send,
                )
                ComposerDictationNotice(state)
                if (state.notice == ComposerNotice.SendFailed) {
                    Text(
                        text = stringResource(Res.string.composer_send_failed),
                        style = OpenCodeType.meta,
                        color = colors.danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerControls(
    state: ComposerState,
    onToggleDictation: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = LocalOpenCodeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Disabled, not hidden: the affordance stays visible so the reason the
        // notice below explains has an anchor.
        Button(
            onClick = onToggleDictation,
            enabled = state.canDictate,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.bgRaised,
                contentColor = colors.text,
            ),
        ) {
            Text(
                text = if (state.dictating) {
                    stringResource(Res.string.composer_dictating)
                } else {
                    stringResource(Res.string.composer_dictate)
                },
                style = OpenCodeType.control,
            )
        }
        Button(
            onClick = onSend,
            enabled = state.canSend,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            ),
        ) {
            Text(stringResource(Res.string.composer_send), style = OpenCodeType.control)
        }
    }
}

@Composable
private fun ComposerDictationNotice(state: ComposerState) {
    val reason = (state.dictation as? DictationAvailability.Unavailable)?.reason ?: return
    val colors = LocalOpenCodeColors.current
    Text(
        text = when (reason) {
            DictationUnavailableReason.PermissionDenied ->
                stringResource(Res.string.composer_dictation_permission)
            else -> stringResource(Res.string.composer_dictation_unavailable)
        },
        style = OpenCodeType.meta,
        color = colors.textMuted,
    )
}
