package org.opencodemobile.features.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencodemobile.shared.application.interaction.ServerCatalogState
import org.opencodemobile.shared.domain.interaction.AgentDescriptor
import org.opencodemobile.shared.domain.interaction.ModelDescriptor
import org.opencodemobile.shared.domain.interaction.ProviderModels
import org.opencodemobile.shared.domain.interaction.ServerCatalog

class CatalogUiStateTest {

    private val populated = ServerCatalog(
        providers = listOf(
            ProviderModels(
                id = "anthropic",
                name = "Anthropic",
                models = listOf(
                    ModelDescriptor(id = "claude-sonnet-4", name = "Claude Sonnet 4", providerId = "anthropic"),
                ),
            ),
        ),
        agents = listOf(AgentDescriptor(name = "build", description = "Default primary agent", mode = "primary")),
    )

    @Test
    fun mapsTheServerCatalogVerbatim() {
        val state = CatalogUiState.from(ServerCatalogState(catalog = populated))

        assertEquals(listOf("anthropic"), state.providers.map { it.id })
        assertEquals("Anthropic", state.providers.single().name)
        assertEquals(listOf("claude-sonnet-4"), state.providers.single().models.map { it.id })
        assertEquals(listOf("build"), state.agents.map { it.name })
        assertTrue(state.hasContent)
        assertNull(state.emptyReason, "a populated catalog needs no empty reason")
        assertNull(state.error)
    }

    @Test
    fun aServerThatExposesNothingKeepsTheEmptyMessage() {
        val state = CatalogUiState.from(
            ServerCatalogState(
                catalog = ServerCatalog(providersAvailable = true, agentsAvailable = true),
            ),
        )

        assertTrue(!state.hasContent)
        assertEquals(ServerCatalogState.EMPTY_MESSAGE, state.emptyReason)
    }

    @Test
    fun aServerWithoutCatalogRoutesKeepsTheUnsupportedMessage() {
        val state = CatalogUiState.from(
            ServerCatalogState(
                catalog = ServerCatalog(providersAvailable = false, agentsAvailable = false),
            ),
        )

        assertTrue(!state.hasContent)
        assertEquals(ServerCatalogState.UNSUPPORTED_MESSAGE, state.emptyReason)
    }

    @Test
    fun aTransientErrorIsShownAsAnErrorNotAsUnsupported() {
        val state = CatalogUiState.from(
            ServerCatalogState(error = "The server is unreachable", loading = false),
        )

        assertEquals("The server is unreachable", state.error)
        assertNull(
            state.emptyReason,
            "a failed read must never be presented as an unsupported surface",
        )
    }
}
