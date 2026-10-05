package org.opencodemobile.shared.domain.interaction

/**
 * Server-exposed model/agent catalog (V1-09; `docs/ARCHITECTURE.md` §3.3).
 *
 * The catalog is **always sourced from the server** — `GET /provider` for
 * models and `GET /agent` for agents. There is deliberately no built-in list
 * anywhere in the app: a server that exposes nothing yields an empty
 * [ServerCatalog] and the screen explains why, instead of falling back to a
 * hard-coded catalog (V1-09 acceptance).
 */

/** One model offered by a provider. */
public data class ModelDescriptor(
    public val id: String,
    public val name: String? = null,
    /** The provider that owns this model. */
    public val providerId: String,
)

/** One provider and the models it offers. */
public data class ProviderModels(
    public val id: String,
    public val name: String? = null,
    public val models: List<ModelDescriptor> = emptyList(),
)

/** One agent offered by the server (`GET /agent`). */
public data class AgentDescriptor(
    public val name: String,
    public val description: String? = null,
    /** Server-reported mode (`primary`, `subagent`, `all`), when present. */
    public val mode: String? = null,
    /** Whether the server hides this agent from the normal picker. */
    public val hidden: Boolean = false,
)

/**
 * What the server offers.
 *
 * [providersAvailable] / [agentsAvailable] distinguish "the server answered
 * with an empty list" from "the server did not expose this surface at all"
 * (for example a route 404 on an older server). Both cases must render as an
 * empty list with an explaining message — never as a populated fallback.
 */
public data class ServerCatalog(
    public val providers: List<ProviderModels> = emptyList(),
    /** The server's default model id per provider (`default` map of `GET /provider`). */
    public val defaultModelByProvider: Map<String, String> = emptyMap(),
    public val agents: List<AgentDescriptor> = emptyList(),
    public val providersAvailable: Boolean = true,
    public val agentsAvailable: Boolean = true,
) {
    /** True when at least one provider exposes at least one model. */
    public val hasModels: Boolean
        get() = providers.any { it.models.isNotEmpty() }

    /** True when the server exposed no model and no agent. */
    public val isEmpty: Boolean
        get() = providers.all { it.models.isEmpty() } && agents.isEmpty()
}
