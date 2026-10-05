package org.opencodemobile.shared.application.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.interaction.AgentDescriptor
import org.opencodemobile.shared.domain.interaction.ModelDescriptor
import org.opencodemobile.shared.domain.interaction.ProviderModels
import org.opencodemobile.shared.domain.interaction.ServerCatalog

class ServerCatalogControllerTest {

    private val catalog = ServerCatalog(
        providers = listOf(
            ProviderModels(
                id = "anthropic",
                name = "Anthropic",
                models = listOf(ModelDescriptor(id = "claude-sonnet-4", name = "Claude Sonnet 4", providerId = "anthropic")),
            ),
        ),
        defaultModelByProvider = mapOf("anthropic" to "claude-sonnet-4"),
        agents = listOf(AgentDescriptor(name = "build", description = "Default primary agent", mode = "primary")),
    )

    @Test
    fun readsModelsAndAgentsFromTheServer() = runTest {
        val controller = ServerCatalogController(FakeInteractionGateway(catalog = catalog))

        val outcome = controller.refresh()

        assertTrue(outcome.isSuccess)
        assertEquals(listOf("anthropic"), controller.state.value.providers.map { it.id })
        assertEquals(listOf("claude-sonnet-4"), controller.state.value.providers.single().models.map { it.id })
        assertEquals(listOf("build"), controller.state.value.agents.map { it.name })
        assertTrue(controller.state.value.hasContent)
        assertNull(controller.state.value.emptyReason, "a populated catalog needs no empty reason")
    }

    @Test
    fun aServerThatExposesNothingYieldsAnEmptyListWithAReason() = runTest {
        // The server answered both routes, with empty payloads.
        val empty = ServerCatalog(providersAvailable = true, agentsAvailable = true)
        val controller = ServerCatalogController(FakeInteractionGateway(catalog = empty))

        controller.refresh()

        assertTrue(controller.state.value.providers.isEmpty())
        assertTrue(controller.state.value.agents.isEmpty())
        assertTrue(!controller.state.value.hasContent)
        assertEquals(ServerCatalogState.EMPTY_MESSAGE, controller.state.value.emptyReason)
    }

    @Test
    fun aServerWithoutCatalogRoutesExplainsTheyAreUnsupported() = runTest {
        val unsupported = ServerCatalog(providersAvailable = false, agentsAvailable = false)
        val controller = ServerCatalogController(FakeInteractionGateway(catalog = unsupported))

        controller.refresh()

        assertTrue(controller.state.value.providers.isEmpty())
        assertTrue(controller.state.value.agents.isEmpty())
        assertEquals(ServerCatalogState.UNSUPPORTED_MESSAGE, controller.state.value.emptyReason)
    }

    @Test
    fun aRefreshFailureIsSurfacedAndLeavesTheListEmpty() = runTest {
        val gateway = FakeInteractionGateway().apply { catalogError = IllegalStateException("offline") }
        val controller = ServerCatalogController(gateway)

        val outcome = controller.refresh()

        assertTrue(outcome.isFailure)
        assertTrue(controller.state.value.providers.isEmpty())
        assertTrue(controller.state.value.agents.isEmpty())
        assertEquals("offline", controller.state.value.error)
    }
}
