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
import org.jetbrains.compose.resources.stringResource
import org.opencodemobile.design.system.LocalOpenCodeColors
import org.opencodemobile.design.system.OpenCodeContext
import org.opencodemobile.design.system.OpenCodeMetrics
import org.opencodemobile.design.system.OpenCodeSpacing
import org.opencodemobile.design.system.OpenCodeTheme
import org.opencodemobile.design.system.OpenCodeType
import org.opencodemobile.design.system.resources.Res
import org.opencodemobile.design.system.resources.ui_agents
import org.opencodemobile.design.system.resources.ui_catalog_description
import org.opencodemobile.design.system.resources.ui_catalog_empty
import org.opencodemobile.design.system.resources.ui_catalog_unavailable
import org.opencodemobile.design.system.resources.ui_catalog_unsupported
import org.opencodemobile.design.system.resources.ui_loading_models_agents
import org.opencodemobile.design.system.resources.ui_models_agents
import org.opencodemobile.design.system.resources.ui_no_models_for_provider
import org.opencodemobile.design.system.resources.ui_retry
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
                Text(stringResource(Res.string.ui_models_agents), style = OpenCodeType.title)
                Text(
                    stringResource(Res.string.ui_catalog_description),
                    style = OpenCodeType.meta,
                    color = colors.textMuted,
                )
                HorizontalDivider(color = colors.line, thickness = OpenCodeMetrics.hairline)

                when {
                    state.error != null -> ErrorState(message = state.error!!, onRetry = onRetry)
                    state.loading && !state.hasContent -> Text(
                        stringResource(Res.string.ui_loading_models_agents),
                        style = OpenCodeType.tech,
                        color = colors.textMuted,
                    )

                    state.emptyReason != null -> Text(
                        text = when (state.emptyReason) {
                            ServerCatalogState.EMPTY_MESSAGE -> stringResource(Res.string.ui_catalog_empty)
                            ServerCatalogState.UNSUPPORTED_MESSAGE -> stringResource(Res.string.ui_catalog_unsupported)
                            else -> state.emptyReason!!
                        },
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
            Text(stringResource(Res.string.ui_retry), style = OpenCodeType.control)
        }
    }
}

@Composable
private fun CatalogBody(state: CatalogUiState) {
    val colors = LocalOpenCodeColors.current
    for (provider in state.providers) {
        Text(provider.name, style = OpenCodeType.section)
        if (provider.models.isEmpty()) {
            Text(stringResource(Res.string.ui_no_models_for_provider), style = OpenCodeType.meta, color = colors.textMuted)
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
        Text(stringResource(Res.string.ui_agents), style = OpenCodeType.section)
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
        Text(stringResource(Res.string.ui_catalog_unavailable))
    }
}
