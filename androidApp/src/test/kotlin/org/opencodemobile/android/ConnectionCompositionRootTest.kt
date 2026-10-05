package org.opencodemobile.android

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.networking.adapter.OpenCodeV2Adapter

/**
 * Composition-root resolver test (OPE-170).
 *
 * `connectionCompositionModule` is the only place allowed to assemble the real
 * [OpenCodeGateway] from `shared/networking` + `shared/security`. OPE-151
 * defect 2 was only caught by review: the composed Ktor client had no JSON
 * content negotiation, so every `body()` call would have failed at runtime.
 *
 * This test drives the production Koin graph (not a hand-built graph) and
 * asserts that the resolved gateway is the sanctioned [OpenCodeV2Adapter] over
 * a client built by `OpenCodeHttpClient.create`, that is, `ContentNegotiation`
 * is installed. A future wiring change that drops the sanctioned factory turns
 * this test red instead of shipping a broken client.
 *
 * The gateway's chain reaches the Android-Keystore-backed `ServerIdentityStore`
 * through `ServerIdentityGate` -> `TofuServerIdentityCoordinator`, so that one
 * platform binding is substituted with an in-memory fake. The gateway wiring
 * under test stays the production one; the test asserts the substituted store
 * is actually used, so the seam cannot silently stop covering the graph.
 */
class ConnectionCompositionRootTest {

    @Test
    fun resolvedGatewayUsesTheSanctionedClientWithJsonContentNegotiation() {
        val identityStore = InMemoryServerIdentityStore()
        val application = koinApplication {
            // Koin 4 enables definition overrides on the application, not on the
            // module: the test module below replaces the Keystore-backed store.
            allowOverride(true)
            modules(connectionCompositionModule, testOverrides(identityStore))
        }
        try {
            val gateway = application.koin.get<OpenCodeGateway>()

            val adapter = assertIs<OpenCodeV2Adapter>(
                gateway,
                "The composition root must bind the sanctioned OpenCodeV2Adapter gateway",
            )
            assertTrue(
                adapter.negotiatesJsonContent(),
                "The composed HttpClient must come from OpenCodeHttpClient.create with JSON " +
                    "content negotiation installed (OPE-151 defect 2)",
            )
        } finally {
            application.close()
        }
    }

    /**
     * Substitutes the Android-Keystore-backed [ServerIdentityStore] with an
     * in-memory fake so the graph resolves on a plain JVM unit test, without an
     * Android context.
     */
    private fun testOverrides(identityStore: ServerIdentityStore): Module = module {
        single<ServerIdentityStore> { identityStore }
    }
}

private class InMemoryServerIdentityStore : ServerIdentityStore {
    private val pins = mutableMapOf<String, ServerFingerprint>()

    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = pins[profileId]

    override suspend fun storePinnedFingerprint(profileId: String, fingerprint: ServerFingerprint) {
        pins[profileId] = fingerprint
    }

    override suspend fun clearPinnedFingerprint(profileId: String) {
        pins.remove(profileId)
    }
}
