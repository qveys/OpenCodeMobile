package org.opencodemobile.features.transcript

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeMetrics
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.shared.application.chat.TranscriptState
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.chat.TranscriptRole

/**
 * The V1-05 transcript: the streamed conversation of one session.
 *
 * It renders [TranscriptState] and holds no logic of its own. `LazyColumn` keys
 * every message by its server id and only composes visible rows, so a long
 * transcript (the `long-transcript` scenario) stays smooth.
 *
 * `docs/DESIGN-SYSTEM.md`: monospace, no social bubbles. The user prompt is a
 * monospace block; assistant text is Markdown with highlighted code fences.
 */
@Composable
public fun TranscriptScreen(
    state: TranscriptState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenCodeColors.current
    when {
        state.isEmpty && state.loading -> TranscriptNotice("Loading transcript…", modifier)

        state.isEmpty -> TranscriptNotice(
            text = state.error ?: "No transcript yet. Send a prompt to start the turn.",
            modifier = modifier,
        )

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = OpenCodeSpacing.x2),
        ) {
            items(state.messages, key = { it.id }) { message ->
                TranscriptMessageItem(message)
                HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
            }
        }
    }
}

@Composable
private fun TranscriptNotice(text: String, modifier: Modifier = Modifier) {
    val colors = LocalOpenCodeColors.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = OpenCodeType.tech,
            color = colors.textMuted,
            modifier = Modifier.padding(OpenCodeSpacing.x4),
        )
    }
}

@Composable
private fun TranscriptMessageItem(message: TranscriptMessage) {
    val colors = LocalOpenCodeColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OpenCodeSpacing.x3, vertical = OpenCodeSpacing.x2),
        verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x1),
    ) {
        Text(
            text = roleLabel(message.role),
            style = OpenCodeType.label,
            color = colors.textMuted,
        )
        when (message.role) {
            TranscriptRole.User -> Text(
                text = message.text,
                style = OpenCodeType.body,
                color = colors.text,
                fontFamily = FontFamily.Monospace,
            )

            else -> AssistantMarkdown(content = message.text)
        }
    }
}

private fun roleLabel(role: TranscriptRole): String = when (role) {
    TranscriptRole.User -> "YOU"
    TranscriptRole.Assistant -> "AGENT"
    TranscriptRole.System -> "SYSTEM"
    TranscriptRole.Tool -> "TOOL"
    TranscriptRole.Unknown -> "MESSAGE"
}
