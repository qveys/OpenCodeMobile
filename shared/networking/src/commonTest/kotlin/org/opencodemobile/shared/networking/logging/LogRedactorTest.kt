package org.opencodemobile.shared.networking.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Threat T4: pure redaction rules. These asserts are the contract the CI gate
 * and the adapter logging interceptor rely on.
 */
class LogRedactorTest {

    private val fakeToken = "sk-test-0123456789abcdef0123456789abcdef"
    private val fakePrompt = "reformat my private source file at /etc/secrets"

    @Test
    fun redactsAuthorizationHeaderValue() {
        val line = "REQUEST: POST https://server/session\nAuthorization: Bearer $fakeToken"

        val redacted = LogRedactor.redact(line)

        assertFalse(redacted.contains(fakeToken), "raw token leaked: $redacted")
        assertTrue(redacted.contains(LogRedactor.REDACTED))
        assertTrue(redacted.contains("POST https://server/session"), "structural metadata lost: $redacted")
    }

    @Test
    fun redactsHeaderLinesWithLogPrefixes() {
        val redacted = LogRedactor.redact("-> Authorization: Basic dXNlcjpwYXNzd29yZA==")

        assertFalse(redacted.contains("dXNlcjpwYXNzd29yZA=="), "basic credential leaked: $redacted")
        assertTrue(redacted.contains(LogRedactor.REDACTED))
    }

    @Test
    fun redactsPromptFieldInJsonBody() {
        val body = """{"sessionID":"s1","prompt":"$fakePrompt","model":"gpt-5"}"""

        val redacted = LogRedactor.redact(body)

        assertFalse(redacted.contains(fakePrompt), "prompt body leaked: $redacted")
        assertTrue(redacted.contains("\"sessionID\":\"s1\""), "non-sensitive fields should survive: $redacted")
        assertTrue(redacted.contains("gpt-5"), "non-sensitive fields should survive: $redacted")
    }

    @Test
    fun redactsSensitiveJsonKeysCaseInsensitively() {
        val body = """{"Content":"top secret text","Token":"abc123def456"}"""

        val redacted = LogRedactor.redact(body)

        assertFalse(redacted.contains("top secret text"), "content leaked: $redacted")
        assertFalse(redacted.contains("abc123def456"), "token leaked: $redacted")
    }

    @Test
    fun redactsBearerTokenOutsideHeaderContext() {
        val redacted = LogRedactor.redact("attaching Bearer $fakeToken to the request")

        assertFalse(redacted.contains(fakeToken), "token leaked: $redacted")
        assertTrue(redacted.contains(LogRedactor.REDACTED))
    }

    @Test
    fun leavesBenignMetadataUntouched() {
        val line = "RESPONSE: 200 OK, content-type: application/json, 512 bytes"

        assertEquals(line, LogRedactor.redact(line))
    }

    @Test
    fun classifiesSensitiveHeaders() {
        assertTrue(LogRedactor.isSensitiveHeader("Authorization"))
        assertTrue(LogRedactor.isSensitiveHeader("x-api-key"))
        assertTrue(LogRedactor.isSensitiveHeader(" Cookie "))
        assertFalse(LogRedactor.isSensitiveHeader("Content-Type"))
        assertFalse(LogRedactor.isSensitiveHeader("Accept"))
    }
}