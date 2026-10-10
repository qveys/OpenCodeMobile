package org.opencodemobile.features.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.ServerFingerprint

private val hostile = "evil‮\n\u0007​" + "A".repeat(500)

private fun fp(seed: Int) = ServerFingerprint.of(ByteArray(32) { (it + seed).toByte() })

class ConnectionFailureTest {
    @Test
    fun sanitizeStripsControlAndBidiAndTruncates() {
        val clean = hostile.sanitizeForDisplay()
        assertFalse(clean.any { it == '‮' || it == '\n' || it == '\u0007' || it == '​' })
        assertEquals(96, clean.length)
    }

    @Test
    fun serverControlledTextNeverReachesTheModel() {
        listOf(
            DomainError.HandshakeIncomplete(hostile),
            DomainError.CredentialRejected(hostile),
            DomainError.PolicyMethodNotAllowed(hostile),
            DomainError.StorageFailure(hostile),
        ).map { it.toConnectionFailure() }.forEach { f ->
            assertNull(f.pinned)
            assertNull(f.presented)
            assertFalse(f.toString().contains("evil"))
        }
    }

    @Test
    fun identityChangeKeepsBothFingerprints() {
        val f = DomainError.IdentityChanged(fp(1), fp(2)).toConnectionFailure()
        assertEquals(FailureKind.CHANGED, f.kind)
        assertEquals(fp(1).colonSeparated, f.pinned)
        assertEquals(fp(2).colonSeparated, f.presented)
        assertTrue(f.pinned!!.none { it == '\n' })
    }
}
