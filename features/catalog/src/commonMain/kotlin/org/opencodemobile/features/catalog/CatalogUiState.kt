package org.opencodemobile.features.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.opencodemobile.shared.application.interaction.ServerCatalogController
import org.opencodemobile.shared.application.interaction.ServerCatalogState
import org.opencodemobile.shared.domain.interaction.AgentDescriptor
import org.opencodemobile.shared.domain.interaction.ModelDescriptor
import org.opencodemobile.shared.domain.interaction.ProviderModels

/** One model offered by a provider, exactly as the server exposed it. */
public data class ModelUi(
    public val id: String,
    public val name: String,
    public val providerId: String,
)

/** One provider and its models, exactly as the server exposed them. */
public data class ProviderUi(
    public val id: String,
    public val name: String,
    public val models: List<ModelUi>,
)

/** One agent offered by the server (`GET /agent`). */
public data class AgentUi(
    public val name: String,
    public val description: String?,
    public val mode: String?,
)

/**
 * What the V1-09 model/agent screen renders.
 *
 * It is a faithful projection of [ServerCatalogState]: the server is the only
 * source of the lists, so an empty catalog keeps [emptyReason] (either "nothing
 * exposed" or "the routes are unsupported") and a failed read keeps [error].
 * [error] always wins over [emptyReason]: a transient outage must never be shown
 * as "this server does not expose model or agent listings" (OPE-194).
 */
public data class CatalogUiState(
    public val providers: List<ProviderUi> = emptyList(),
    public val agents: List<AgentUi> = emptyList(),
    public val loading: Boolean = false,
    public val error: String? = null,
    public val emptyReason: String? = null,
) {
    /** True when the server exposed at least one model or one agent. */
    public val hasContent: Boolean
        get() = providers.any { it.models.isNotEmpty() } || agents.isNotEmpty()

    public companion object {
        public fun from(state: ServerCatalogState): CatalogUiState = CatalogUiState(
            providers = state.providers.map { it.toUi() },
            agents = state.agents.map { it.toUi() },
            loading = state.loading,
            error = state.error,
            // Only an actually-empty, error-free catalog carries an explaining
            // message. A failed read is an error, not an unsupported surface.
            emptyReason = if (state.error == null) state.emptyReason else null,
        )
    }
}

private fun ProviderModels.toUi(): ProviderUi = ProviderUi(
    id = id,
    name = name?.takeIf { it.isNotBlank() } ?: id,
    models = models.map { it.toUi() },
)

private fun ModelDescriptor.toUi(): ModelUi = ModelUi(
    id = id,
    name = name?.takeIf { it.isNotBlank() } ?: id,
    providerId = providerId,
)

private fun AgentDescriptor.toUi(): AgentUi = AgentUi(
    name = name,
    description = description,
    mode = mode,
)

/**
 * State holder the Compose layer observes.
 *
 * It is intentionally thin: the server-is-source-of-truth rule and the error /
 * empty distinction live in [ServerCatalogController]; the presenter only maps
 * state to the view model and forwards the refresh intent.
 */
public class CatalogPresenter(
    private val controller: ServerCatalogController,
    private val scope: CoroutineScope,
) {
    public val state: StateFlow<CatalogUiState> =
        controller.state
            .map { CatalogUiState.from(it) }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = CatalogUiState.from(controller.state.value),
            )

    /** Re-reads `GET /provider` and `GET /agent`. Called on start and reconnect. */
    public fun refresh(directory: String? = null) {
        scope.launch { controller.refresh(directory) }
    }
}
