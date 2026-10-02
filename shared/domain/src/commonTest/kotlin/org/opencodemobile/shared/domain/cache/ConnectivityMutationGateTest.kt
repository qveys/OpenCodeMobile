package org.opencodemobile.shared.domain.cache

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D8 — offline is read-only: every mutation surface must consult the gate and
 * see mutations disabled while offline.
 */
class ConnectivityMutationGateTest {

    @Test
    fun mutationsAreDisabledWhileOffline() {
        val gate = ConnectivityMutationGate(ConnectionState.Offline)

        assertFalse(gate.mutationsAllowed())

        gate.onConnectionStateChanged(ConnectionState.Online)
        assertTrue(gate.mutationsAllowed())

        gate.onConnectionStateChanged(ConnectionState.Offline)
        assertFalse(gate.mutationsAllowed())
    }

    @Test
    fun offlineIsTheDefaultState() {
        assertFalse(ConnectivityMutationGate().mutationsAllowed())
    }
}
