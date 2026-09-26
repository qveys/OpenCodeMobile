package org.opencodemobile.shared.security.identity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.opencodemobile.shared.domain.connection.ServerFingerprint

class SpkiPinCheckTest {

    private val a = ServerFingerprint.of(ByteArray(32) { it.toByte() })
    private val b = ServerFingerprint.of(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun nullPinAcceptsAnyFingerprintForFirstContactCapture() {
        assertTrue(SpkiPinCheck.matches(pinned = null, presented = a))
        assertTrue(SpkiPinCheck.matches(pinned = null, presented = b))
    }

    @Test
    fun pinnedValueAcceptsOnlyAnExactMatch() {
        assertTrue(SpkiPinCheck.matches(pinned = a, presented = a))
        assertFalse(SpkiPinCheck.matches(pinned = a, presented = b))
    }
}