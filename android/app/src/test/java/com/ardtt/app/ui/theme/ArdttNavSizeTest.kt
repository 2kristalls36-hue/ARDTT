package com.ardtt.app.ui.theme

import com.ardtt.app.ui.components.layout.ArdttHeaderDefaults
import com.ardtt.app.ui.components.layout.ArdttNavChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttNavSizeTest {
    @Test
    fun navZoneIsTrackPlusOuterVerticalPadding() {
        assertEquals(56, ArdttSize.NavTrack.value.toInt())
        assertEquals(72, ArdttSize.NavZone.value.toInt())
        assertEquals(
            ArdttSize.NavTrack + ArdttSpacing.Small * 2,
            ArdttSize.NavZone,
        )
    }

    @Test
    fun navLabelStaysTwelveSp() {
        assertEquals(12, ArdttNavChrome.LabelSize.value.toInt())
        assertEquals(14, ArdttNavChrome.LabelLineHeight.value.toInt())
    }

    @Test
    fun headerTitleRowMeetsTouchTarget() {
        assertTrue(ArdttHeaderDefaults.TitleRowHeight >= ArdttSize.TouchTarget)
        assertEquals(48, ArdttSize.TouchTarget.value.toInt())
    }
}
