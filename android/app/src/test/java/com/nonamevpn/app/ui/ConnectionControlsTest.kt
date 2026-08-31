package com.nonamevpn.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionControlsTest {
    @Test
    fun lockedWhileConnectedUnlessUnlocked() {
        assertTrue(connectionControlsLocked(sessionActive = true, unlockWhileConnected = false))
        assertFalse(connectionControlsLocked(sessionActive = true, unlockWhileConnected = true))
        assertFalse(connectionControlsLocked(sessionActive = false, unlockWhileConnected = false))
        assertFalse(connectionControlsLocked(sessionActive = false, unlockWhileConnected = true))
    }

    @Test
    fun tunnelParamsHiddenWhenQuickSettingsHidden() {
        assertFalse(tunnelConnectionParamsVisible(hideQuickSettings = true))
        assertTrue(tunnelConnectionParamsVisible(hideQuickSettings = false))
    }

    @Test
    fun latestCodeTakesMaximum() {
        assertEquals(131, latestAppVersionCode(130, 131))
        assertEquals(130, latestAppVersionCode(130, 0))
        assertEquals(131, latestAppVersionCode(0, 131))
        assertEquals(0, latestAppVersionCode(0, 0))
    }
}
