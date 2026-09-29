package org.opencodemobile.shared.domain.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTitlePolicyTest {

    @Test
    fun normalizeTrimsAndCollapsesWhitespace() {
        assertEquals("Renamed title", SessionTitlePolicy.normalize("  Renamed \t title \n"))
    }

    @Test
    fun blankTitlesAreInvalid() {
        assertNull(SessionTitlePolicy.normalize("   "))
        assertFalse(SessionTitlePolicy.isValid(""))
        assertFalse(SessionTitlePolicy.isValid("   "))
    }

    @Test
    fun titlesLongerThanTheMaximumAreRejected() {
        val tooLong = "a".repeat(SessionTitlePolicy.MAX_LENGTH + 1)
        assertFalse(SessionTitlePolicy.isValid(tooLong))
        assertTrue(SessionTitlePolicy.isValid("a".repeat(SessionTitlePolicy.MAX_LENGTH)))
    }

    @Test
    fun forkUnavailableIsTheFailClosedDefault() {
        assertFalse(SessionCapabilities.Unknown.forkAvailable)
    }
}
