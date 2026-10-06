package org.opencodemobile.features.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeMetrics
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.shared.application.interaction.ServerCatalogState

/**
 * The V1-09 model/agent surface.
 *
 * It renders exactly what the server exposed: [CatalogUiState.providers] and
 * [CatalogUiState.agents] are projections of `GET /provider` and `GET /agent`,
 * with **no built-in catalog**. An empty server shows [CatalogUiState.emptyReason]
 * ([ServerCatalogState.EMPTY_MESSAGE] vs [ServerCatalogState.UNSUPPORTED_MESSAGE]),
 * and a failed read shows [CatalogUiState.error] with a retry — a transient
 * outage is never presented as "unsupported".
 *
 * The screen is stateless: it renders [state] and forwards the retry intent.
 */
@Composable
public fun ServerCatalogScreen(
    state: CatalogUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeTheme(context = OpenCodeContext.Chrome) {
        val colors = LocalOpenCodeColors.current
        Surface(
            modifier = modifier.fillMaxSize(),
            color = colors.bg,
            contentColor = colors.text,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(OpenCodeSpacing.x4),
                verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.x3),
            ) {
                Text("Models & agents", style = OpenCodeType.title)
                Text(
                    "What this server exposes. Nothing is bundled with the app.",
                    style = OpenCodeType.meta,
                    color = colors.textMuted,
                )
                HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)

                when {
                    state.error != null -> ErrorState(message = state.error!!, onRetry = onRetry)
                    state.loading && !state.hasContent -> Text(
                        "Loading models and agents…",
                        style = OpenCodeType.tech,
                        color = colors.textMuted,
                    )

                    state.emptyReason != null -> Text(
                        text = state.emptyReason!!,
                        style = OpenCodeType.body,
                        color = colors.textMuted,
                    )

                    else -> CatalogBody(state = state)
                }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    val colors = LocalOpenCodeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = OpenCodeType.body,
            color = colors.danger,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text("Retry", style = OpenCodeType.control)
        }
    }
}

@Composable
private fun CatalogBody(state: CatalogUiState) {
    val colors = LocalOpenCodeColors.current
    for (provider in state.providers) {
        Text(provider.name, style = OpenCodeType.section)
        if (provider.models.isEmpty()) {
            Text("No models exposed by this provider.", style = OpenCodeType.meta, color = colors.textMuted)
        } else {
            for (model in provider.models) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(model.name, style = OpenCodeType.body, modifier = Modifier.weight(1f))
                    Text(model.id, style = OpenCodeType.tech, color = colors.textMuted)
                }
            }
        }
        HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)
    }

    if (state.agents.isNotEmpty()) {
        Text("Agents", style = OpenCodeType.section)
        for (agent in state.agents) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(agent.name, style = OpenCodeType.bodyStrong, modifier = Modifier.weight(1f))
                    agent.mode?.let { mode ->
                        Text(mode, style = OpenCodeType.meta, color = colors.textMuted)
                    }
                }
                agent.description?.let { description ->
                    Text(description, style = OpenCodeType.meta, color = colors.textBody)
                }
            }
        }
    }
}

/** A neutral placeholder used while the catalog graph is not wired. */
@Composable
public fun ServerCatalogPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Models & agents are unavailable until a server is connected.")
    }
}
