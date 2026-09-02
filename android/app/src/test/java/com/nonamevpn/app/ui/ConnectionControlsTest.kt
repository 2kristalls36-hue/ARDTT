package com.nonamevpn.app.ui

import com.nonamevpn.app.core.ConnPathMode
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

    @Test
    fun bypassChipAsksForCallHashWhenMissing() {
        assertTrue(pathModeNeedsCallHash(ConnPathMode.Bypass, hasCallHash = false))
        assertFalse(pathModeNeedsCallHash(ConnPathMode.Bypass, hasCallHash = true))
        assertFalse(pathModeNeedsCallHash(ConnPathMode.Auto, hasCallHash = false))
        assertFalse(pathModeNeedsCallHash(ConnPathMode.Direct, hasCallHash = false))
    }

    @Test
    fun themeModeCyclesThroughSystemLightDark() {
        assertEquals("light", nextThemeMode("system"))
        assertEquals("dark", nextThemeMode("light"))
        assertEquals("system", nextThemeMode("dark"))
        assertEquals("light", nextThemeMode("SYSTEM"))
        assertEquals("light", nextThemeMode("unknown"))
        assertEquals("auto", themeModeVisualKey("system"))
        assertEquals("light", themeModeVisualKey("light"))
        assertEquals("dark", themeModeVisualKey("dark"))
    }
}
