package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainErrorMessagesTest {

    @Test
    fun incompatibleServerShowsVersionAndSupportedRange() {
        val error = DomainError.ServerIncompatible(ServerVersion(2, 3, 0), "1.x (>= 1.18.0)")

        val presentation = DomainErrorMessages.present(error)

        assertTrue(presentation.message.contains("2.3.0"), presentation.message)
        assertTrue(presentation.message.contains("1.x (>= 1.18.0)"), presentation.message)
        assertFalse(presentation.retryable)
    }

    @Test
    fun identityChangedShowsBothFingerprints() {
        val previous = ServerFingerprint.of(ByteArray(32) { 1 })
        val presented = ServerFingerprint.of(ByteArray(32) { 2 })

        val presentation = DomainErrorMessages.present(DomainError.IdentityChanged(previous, presented))

        assertTrue(presentation.message.contains(previous.colonSeparated), presentation.message)
        assertTrue(presentation.message.contains(presented.colonSeparated), presentation.message)
    }

    @Test
    fun policyRejectionExplainsPlainHttpIsRefused() {
        val error = DomainError.PolicyRejected(
            scope = ServerNetworkScope.Public,
            violation = HttpPolicyViolation.PublicPlaintextHttp,
        )

        val presentation = DomainErrorMessages.present(error)

        assertTrue(presentation.message.contains("Plain HTTP"), presentation.message)
        assertTrue(presentation.actionHint!!.contains("HTTPS"), presentation.actionHint)
    }

    @Test
    fun addressValidationExplainsThePortRange() {
        val error = DomainError.InvalidServerAddress(
            problem = ServerInputProblem.PORT_OUT_OF_RANGE,
            raw = "host:70000",
            field = ServerAddressField.PORT,
        )

        val presentation = DomainErrorMessages.present(error)

        assertTrue(presentation.message.contains("65535"), presentation.message)
        assertTrue(presentation.messageKey.endsWith("port_out_of_range"), presentation.messageKey)
    }

    @Test
    fun everyErrorProducesKeysAndConsistentRetryability() {
        val samples = listOf(
            DomainError.Unreachable(null),
            DomainError.ServerUnhealthy(),
            DomainError.HandshakeIncomplete("detail"),
            DomainError.ServerIncompatible(ServerVersion(2, 0, 0), "1.x (>= 1.18.0)"),
            DomainError.CredentialRejected(),
            DomainError.AuthenticationRequired(),
            DomainError.IdentityUnconfirmed(ServerFingerprint.of(ByteArray(32))),
            DomainError.IdentityChanged(
                ServerFingerprint.of(ByteArray(32) { 1 }),
                ServerFingerprint.of(ByteArray(32) { 2 }),
            ),
            DomainError.IdentityNotVerifiable(),
            DomainError.PolicyRejected(ServerNetworkScope.Public, HttpPolicyViolation.PublicPlaintextHttp),
            DomainError.PolicyMethodNotAllowed("PUT"),
            DomainError.InvalidServerAddress(ServerInputProblem.BLANK, ""),
            DomainError.InvalidImportLink(ServerInputProblem.MALFORMED, "x"),
            DomainError.StorageFailure("detail"),
            DomainError.Unknown(null),
        )

        for (error in samples) {
            val presentation = DomainErrorMessages.present(error)
            assertTrue(presentation.titleKey.isNotBlank(), "titleKey for $error")
            assertTrue(presentation.messageKey.isNotBlank(), "messageKey for $error")
            assertTrue(presentation.title.isNotBlank(), "title for $error")
            assertTrue(presentation.message.isNotBlank(), "message for $error")
            assertEquals(error.isRetryable, presentation.retryable, "retryable for $error")
        }
    }
}
