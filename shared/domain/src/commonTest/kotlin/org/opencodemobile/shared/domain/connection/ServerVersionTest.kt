package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerVersionTest {

    @Test
    fun parsesAFullVersion() {
        assertEquals(ServerVersion(1, 18, 32), ServerVersion.parseOrNull("1.18.32"))
    }

    @Test
    fun missingComponentsDefaultToZero() {
        assertEquals(ServerVersion(2, 0, 0), ServerVersion.parseOrNull("2"))
        assertEquals(ServerVersion(2, 3, 0), ServerVersion.parseOrNull("2.3"))
    }

    @Test
    fun ignoresSurroundingWhitespace() {
        assertEquals(ServerVersion(1, 0, 0), ServerVersion.parseOrNull("  1.0.0 "))
    }

    @Test
    fun rejectsUnparseableInput() {
        assertNull(ServerVersion.parseOrNull(""))
        assertNull(ServerVersion.parseOrNull("   "))
        assertNull(ServerVersion.parseOrNull("abc"))
        assertNull(ServerVersion.parseOrNull("1.2.3.4"))
        assertNull(ServerVersion.parseOrNull("1..2"))
        assertNull(ServerVersion.parseOrNull("v1.2.3"))
        assertNull(ServerVersion.parseOrNull("1.x"))
        assertNull(ServerVersion.parseOrNull("+1"))
        assertNull(ServerVersion.parseOrNull("1.-2"))
    }

    @Test
    fun comparesNumericallyNotLexically() {
        assertTrue(ServerVersion(1, 18, 32) > ServerVersion(1, 9, 9))
        assertTrue(ServerVersion(1, 18, 32) > ServerVersion(1, 18, 9))
        assertTrue(ServerVersion(2, 0, 0) > ServerVersion(1, 99, 99))
        assertEquals(0, ServerVersion(1, 18, 0).compareTo(ServerVersion(1, 18, 0)))
    }
}
