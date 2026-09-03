package com.nonamevpn.app.ui

import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnState
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
    fun profileSwitchBlockedDuringLiveVpnSession() {
        assertTrue(vpnSessionBlocksProfileSwitch(ConnState.Connected))
        assertTrue(vpnSessionBlocksProfileSwitch(ConnState.Connecting))
        assertTrue(vpnSessionBlocksProfileSwitch(ConnState.Disconnecting))
        assertTrue(vpnSessionBlocksProfileSwitch(ConnState.PausedTrustedWifi))
        assertFalse(vpnSessionBlocksProfileSwitch(ConnState.Ready))
        assertFalse(vpnSessionBlocksProfileSwitch(ConnState.Idle))
        assertFalse(vpnSessionBlocksProfileSwitch(ConnState.Probing))
        assertFalse(vpnSessionBlocksProfileSwitch(ConnState.Error))
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
        assertTrue(themeModeIsDark("dark", systemDark = false))
        assertFalse(themeModeIsDark("light", systemDark = true))
        assertTrue(themeModeIsDark("system", systemDark = true))
        assertFalse(themeModeIsDark("SYSTEM", systemDark = false))
        assertTrue(themeModeIsDark("unknown", systemDark = true))
    }

    @Test
    fun pathModeHelpUsesConnPathModeNotRawAliases() {
        assertEquals(
            "Используется только прямое подключение.",
            PathModeCopy.help("direct", hasCallHash = true, compact = false),
        )
        assertEquals(
            "Только прямое подключение.",
            PathModeCopy.help("awg", hasCallHash = false, compact = true),
        )
        assertEquals(
            "Приоритет прямого подключения, резерв — обход.",
            PathModeCopy.help("auto", hasCallHash = false, compact = false),
        )
        assertEquals(
            "Сначала прямое, при недоступности — обход.",
            PathModeCopy.help("auto", hasCallHash = true, compact = true),
        )
        assertEquals(
            "Используется только обход. Требуется код звонка.",
            PathModeCopy.help("bypass", hasCallHash = true, compact = false),
        )
        assertEquals(
            "Код звонка не задан. Нажмите «Обход», чтобы открыть карточку.",
            PathModeCopy.help("wdtt", hasCallHash = false, compact = true),
        )
        assertEquals(
            "Код звонка не задан. Нажмите «Обход», чтобы перейти к карточке метода обхода.",
            PathModeCopy.help("bypass", hasCallHash = false, compact = false),
        )
    }
}
