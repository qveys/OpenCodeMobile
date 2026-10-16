package org.opencodemobile.shared.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompatibilityProfileTest {

    private val profile = CompatibilityProfile.OpenCodeServerV2

    @Test
    fun thePinnedVersionIsCompatible() {
        assertEquals(
            CompatibilityResult.Compatible(ServerVersion(2, 0, 6)),
            profile.evaluate(ServerVersion(2, 0, 6)),
        )
    }

    @Test
    fun theMinimumVersionIsCompatible() {
        assertTrue(profile.evaluate(ServerVersion(2, 0, 0)) is CompatibilityResult.Compatible)
    }

    @Test
    fun newerVersionsInTheSameMajorLineAreForwardCompatible() {
        assertTrue(profile.evaluate(ServerVersion(2, 1, 0)) is CompatibilityResult.Compatible)
        assertTrue(profile.evaluate(ServerVersion(2, 99, 99)) is CompatibilityResult.Compatible)
    }

    @Test
    fun versionsOlderThanTheMinimumAreIncompatible() {
        val result = profile.evaluate(ServerVersion(1, 99, 9))
        assertTrue(result is CompatibilityResult.Incompatible)
        assertEquals(ServerVersion(1, 99, 9), (result as CompatibilityResult.Incompatible).serverVersion)
    }

    @Test
    fun aDifferentMajorLineIsIncompatible() {
        assertTrue(profile.evaluate(ServerVersion(3, 0, 0)) is CompatibilityResult.Incompatible)
        assertTrue(profile.evaluate(ServerVersion(1, 18, 32)) is CompatibilityResult.Incompatible)
    }

    @Test
    fun aMissingVersionIsUnknownNotCompatible() {
        assertEquals(CompatibilityResult.Unknown, profile.evaluate(null))
    }

    @Test
    fun theSupportedRangeIsHumanReadable() {
        assertEquals("2.x (>= 2.0.0)", profile.supportedRange)
    }
}
