package com.ardtt.app

import android.content.Intent
import com.ardtt.app.core.ConnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetToggleTest {
    @Test
    fun runningCoversActiveTunnelStates() {
        assertTrue(widgetTunnelIsRunning(ConnState.Connected))
        assertTrue(widgetTunnelIsRunning(ConnState.Connecting))
        assertTrue(widgetTunnelIsRunning(ConnState.PausedTrustedWifi))
        assertFalse(widgetTunnelIsRunning(ConnState.Idle))
        assertFalse(widgetTunnelIsRunning(ConnState.Ready))
        assertFalse(widgetTunnelIsRunning(ConnState.Error))
    }

    @Test
    fun launchFlagsDoNotJoinOrAnimateTheAppTask() {
        val flags = widgetToggleLaunchFlags()
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
