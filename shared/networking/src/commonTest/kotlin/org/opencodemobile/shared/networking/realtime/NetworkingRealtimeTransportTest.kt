package org.opencodemobile.shared.networking.realtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.event.RawServerEvent
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NetworkingRealtimeTransportTest {
    @Test
    fun connectedAndHeartbeatBecomeInternalSignals() = runTest {
        val body = "data: {\"id\":\"connected-id\",\"type\":\"server.connected\",\"data\":{}}\n\n" +
            ": heartbeat\n\n" +
            "data: {\"id\":\"business-id\",\"type\":\"session.status\",\"data\":{}}\n\n"
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })
        try {
            val records = mutableListOf<RawServerEvent>()
            NetworkingRealtimeTransport(client, "http://localhost").openEventStream().collect { records += it }
            assertEquals(RawServerEvent.opened(), records[0])
            assertEquals(RawServerEvent.activity(), records[1])
            assertEquals("session.status", records[2].type)
            assertEquals("business-id", records[2].id)
            assertEquals(3, records.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellationClosesStreamingResponse() = runTest {
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })
        try {
            val received = CompletableDeferred<Unit>()
            val job = launch {
                NetworkingRealtimeTransport(client, "http://localhost").openEventStream().collect { if (it.signal == null) received.complete(Unit) }
            }
            body.writeStringUtf8("data: {\"type\":\"session.status\"}\n\n")
            received.await()
            job.cancelAndJoin()
            assertTrue(body.isClosedForRead)
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectedResponseClosesItsBody() = runTest {
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.Unauthorized) })
        try {
            assertFailsWith<RealtimeTransportException> {
                NetworkingRealtimeTransport(client, "http://localhost").openEventStream().collect()
            }
            assertTrue(body.isClosedForRead)
        } finally {
            client.close()
        }
    }
}
