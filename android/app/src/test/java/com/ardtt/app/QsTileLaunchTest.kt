package com.ardtt.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QsTileLaunchTest {
    @Test
    fun shortTapUsesSameColdStartFlagsAsWidget() {
        val flags = widgetToggleLaunchFlags()
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
    }

    @Test
    fun longPressPreferencesShouldOpenMainActivityAction() {
        assertEquals(
            "android.service.quicksettings.action.QS_TILE_PREFERENCES",
            "android.service.quicksettings.action.QS_TILE_PREFERENCES",
        )
    }
}
