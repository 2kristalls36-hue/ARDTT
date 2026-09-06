package com.ardtt.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ArdttNavSizeTest {
    @Test
    fun navZoneIsTrackPlusOuterVerticalPadding() {
        assertEquals(48, ArdttSize.NavTrack.value.toInt())
        assertEquals(64, ArdttSize.NavZone.value.toInt())
        assertEquals(
            ArdttSize.NavTrack + ArdttSpacing.Small * 2,
            ArdttSize.NavZone,
        )
    }
}
