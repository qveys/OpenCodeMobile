package org.opencodemobile.shared.networking.client.generated.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * OPE-16: initial test that verifies the KMP test runner works.
 *
 * This lives in `commonTest`, so it executes on `androidUnitTest` (JVM) and on
 * the Apple targets from the same revision. A kotlinx.serialization round-trip
 * is deliberate: it proves both the kotlin.test harness and the serialization
 * plugin are wired identically on every target.
 */
class GeneratedModelsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun healthResponseRoundTrips() {
        val wire = """{"healthy":true,"version":"1.18.32"}"""

        val decoded = json.decodeFromString(ApiHealthResponse.serializer(), wire)

        assertEquals(ApiHealthResponse(healthy = true, version = "1.18.32"), decoded)
        assertEquals(wire, json.encodeToString(ApiHealthResponse.serializer(), decoded))
    }

    @Test
    fun sessionDecodesOptionalFieldsAsNullAndNestedTime() {
        val decoded = json.decodeFromString(
            ApiSession.serializer(),
            """{"id":"s1","time":{"created":10,"updated":42}}""",
        )

        assertEquals("s1", decoded.id)
        assertNull(decoded.title)
        assertNull(decoded.parentID)
        assertEquals(ApiSessionTime(created = 10, updated = 42), decoded.time)
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val decoded = json.decodeFromString(
            ApiHealthResponse.serializer(),
            """{"healthy":false,"version":"1.18.32","futureField":123}""",
        )

        assertEquals(ApiHealthResponse(healthy = false, version = "1.18.32"), decoded)
    }
}