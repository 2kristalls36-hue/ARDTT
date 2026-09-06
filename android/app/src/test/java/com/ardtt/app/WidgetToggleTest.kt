package com.ardtt.app

import com.ardtt.app.core.ConnState
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
}
