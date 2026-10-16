package org.opencodemobile.shared.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RawServerEventTest {
    @Test
    fun transportSignalsCarryNoBusinessEnvelope() {
        assertEquals(StreamSignal.Opened, RawServerEvent.opened().signal)
        assertEquals(StreamSignal.Activity, RawServerEvent.activity().signal)
        for (record in listOf(RawServerEvent.opened(), RawServerEvent.activity())) {
            assertNull(record.id)
            assertEquals("", record.type)
            assertEquals("", record.data)
        }
        assertNull(RawServerEvent("evt_1", "session.status", "{}").signal)
    }
}
