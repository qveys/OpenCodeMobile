package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DomainErrorTest {

    private val profile = CompatibilityProfile.OpenCodeServerV2
    private val presented = ServerFingerprint.of(ByteArray(32) { (it + 1).toByte() })
    private val previous = ServerFingerprint.of(ByteArray(32) { (it + 2).toByte() })

    @Test
    fun unreachableMapsFromHealthUnavailable() {
        val mapped = HandshakeException.HealthUnavailable(IllegalStateException("refused")).toDomainError()

        val error = assertIs<DomainError.Unreachable>(mapped)
        assertEquals(DomainErrorCode.CONNECTION_UNREACHABLE, error.code)
        assertEquals(DomainErrorCategory.CONNECTION, error.category)
        assertTrue(error.isRetryable)
    }

    @Test
    fun unhealthyMapsFromServerUnhealthy() {
        val error = assertIs<DomainError.ServerUnhealthy>(HandshakeException.ServerUnhealthy().toDomainError())
        assertEquals(DomainErrorCode.CONNECTION_SERVER_UNHEALTHY, error.code)
        assertTrue(error.isRetryable)
    }

    @Test
    fun incompleteMapsFromIncompleteHandshake() {
        val error = assertIs<DomainError.HandshakeIncomplete>(
            HandshakeException.Incomplete("no version").toDomainError(),
        )
        assertEquals(DomainErrorCode.CONNECTION_HANDSHAKE_INCOMPLETE, error.code)
        assertFalse(error.isRetryable)
    }

    @Test
    fun incompatibleMapsWithVersionAndSupportedRange() {
        val source = HandshakeException.Incompatible(ServerVersion(2, 0, 0), profile)

        val error = assertIs<DomainError.ServerIncompatible>(source.toDomainError())

        assertEquals(DomainErrorCode.COMPATIBILITY_SERVER_INCOMPATIBLE, error.code)
        assertEquals(DomainErrorCategory.COMPATIBILITY, error.category)
        assertEquals(ServerVersion(2, 0, 0), error.serverVersion)
        assertEquals(profile.supportedRange, error.supportedRange)
        assertFalse(error.isRetryable)
    }

    @Test
    fun policyRejectionMapsWithScopeAndViolation() {
        val decision = HttpConnectionPolicyDecision.Rejected(
            scope = ServerNetworkScope.Public,
            violation = HttpPolicyViolation.PublicPlaintextHttp,
        )

        val error = assertIs<DomainError.PolicyRejected>(
            ConnectionPolicyException.Rejected(decision).toDomainError(),
        )

        assertEquals(DomainErrorCode.POLICY_PUBLIC_PLAINTEXT_HTTP_REJECTED, error.code)
        assertEquals(DomainErrorCategory.POLICY, error.category)
        assertEquals(ServerNetworkScope.Public, error.scope)
        assertEquals(HttpPolicyViolation.PublicPlaintextHttp, error.violation)
    }

    @Test
    fun identityFailuresMapToTheirCodes() {
        val unconfirmed = assertIs<DomainError.IdentityUnconfirmed>(
            ServerIdentityException.ConfirmationRequired(presented).toDomainError(),
        )
        assertEquals(presented, unconfirmed.presented)

        val changed = assertIs<DomainError.IdentityChanged>(
            ServerIdentityException.IdentityChanged(previous, presented).toDomainError(),
        )
        assertEquals(previous, changed.previous)
        assertEquals(presented, changed.presented)

        val notVerifiable = assertIs<DomainError.IdentityNotVerifiable>(
            ServerIdentityException.NoCertificatePresented().toDomainError(),
        )
        assertEquals(DomainErrorCode.IDENTITY_NOT_VERIFIABLE, notVerifiable.code)
    }

    @Test
    fun anExistingDomainErrorIsReturnedUnchanged() {
        val error = DomainError.AuthenticationRequired()

        assertSame(error, error.toDomainError())
    }

    @Test
    fun anUnknownFailureBecomesTheUnknownCatchAll() {
        val cause = IllegalStateException("boom")

        val error = assertIs<DomainError.Unknown>(cause.toDomainError())

        assertEquals(DomainErrorCode.UNKNOWN, error.code)
        assertEquals(DomainErrorCategory.UNKNOWN, error.category)
        assertSame(cause, error.cause)
    }

    @Test
    fun everyCodeHasACategoryAndWireValue() {
        for (code in DomainErrorCode.entries) {
            assertTrue(code.wireValue.isNotBlank(), "empty wire value for $code")
            assertEquals(code.wireValue, code.wireValue.lowercase())
            assertFalse(code.wireValue.contains('_'), "wire value must not keep underscores: ${code.wireValue}")
        }
    }
}
