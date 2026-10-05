package org.opencodemobile.shared.application.interaction

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencodemobile.shared.domain.interaction.AgentDescriptor
import org.opencodemobile.shared.domain.interaction.OpenCodeInteractionGateway
import org.opencodemobile.shared.domain.interaction.ProviderModels
import org.opencodemobile.shared.domain.interaction.ServerCatalog

/**
 * State of the server-exposed model/agent catalog (V1-09).
 *
 * [providers] and [agents] hold **only** what the server returned. When both
 * are empty the state carries an [emptyReason] instead of a built-in fallback
 * list.
 */
public data class ServerCatalogState(
    public val catalog: ServerCatalog? = null,
    public val loading: Boolean = false,
    public val error: String? = null,
) {
    public val providers: List<ProviderModels>
        get() = catalog?.providers.orEmpty()

    public val agents: List<AgentDescriptor>
        get() = catalog?.agents.orEmpty()

    /** True when the server exposed at least one model or one agent. */
    public val hasContent: Boolean
        get() = providers.any { it.models.isNotEmpty() } || agents.isNotEmpty()

    /**
     * A message explaining an empty catalog. Null while there is content, while
     * loading, or before the first refresh.
     */
    public val emptyReason: String?
        get() {
            val current = catalog ?: return null
            if (hasContent) return null
            return if (!current.providersAvailable && !current.agentsAvailable) {
                UNSUPPORTED_MESSAGE
            } else {
                EMPTY_MESSAGE
            }
        }

    public companion object {
        /** The server answered, but exposed no model and no agent. */
        public const val EMPTY_MESSAGE: String =
            "This server did not expose any models or agents."

        /** The server does not expose the catalog routes at all. */
        public const val UNSUPPORTED_MESSAGE: String =
            "This server does not expose model or agent listings."
    }
}

/**
 * Reads what the server offers (V1-09) and exposes it as state.
 *
 * The controller never invents entries: it maps [OpenCodeInteractionGateway.serverCatalog]
 * verbatim, so a server that exposes nothing produces an empty list plus
 * [ServerCatalogState.emptyReason]. It is read-only, so it is not gated by the
 * connectivity mutation gate.
 */
public class ServerCatalogController(
    private val gateway: OpenCodeInteractionGateway,
) {
    private val _state = MutableStateFlow(ServerCatalogState())

    public val state: StateFlow<ServerCatalogState> = _state.asStateFlow()

    /** Re-reads the catalog from the server. Call on start and reconnect. */
    public suspend fun refresh(directory: String? = null): Result<Unit> {
        _state.value = _state.value.copy(loading = true, error = null)
        return runCatchingNonCancellable { gateway.serverCatalog(directory) }.fold(
            onSuccess = { catalog ->
                _state.value = ServerCatalogState(catalog = catalog)
                Result.success(Unit)
            },
            onFailure = { failure ->
                _state.value = _state.value.copy(loading = false, error = failure.messageOrType())
                Result.failure(failure)
            },
        )
    }
}
