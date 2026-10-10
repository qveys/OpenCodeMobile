package org.opencodemobile.design.system

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the Android touch-target token and shared clickable session row. */
class AndroidTouchTargetContractTest {
    @Test
    fun androidTouchTargetTokenIs48Dp() {
        assertEquals(48.dp, OpenCodeMetrics.hitAndroid)
    }

    @Test
    fun clickableSessionRowMeetsTheAndroidTouchTarget() {
        assertTrue(
            OpenCodeMetrics.rowMin >= OpenCodeMetrics.hitAndroid,
            "SessionRow minimum height must meet the Android 48 dp target",
        )
    }
}
