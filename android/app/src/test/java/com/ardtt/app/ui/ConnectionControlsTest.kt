package com.ardtt.app.ui

import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.ConnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun idleProbeKeepsConnectChromeNotStop() {
        assertEquals("Подключиться", tunnelStickyCtaLabel(ConnState.Probing))
        assertEquals("Подключиться", tunnelStickyCtaLabel(ConnState.Idle))
        assertEquals("Подключиться", tunnelStickyCtaLabel(ConnState.Ready))
        assertEquals("Отменить", tunnelStickyCtaLabel(ConnState.Connecting))
        assertEquals("Отключить", tunnelStickyCtaLabel(ConnState.Connected))
        assertEquals("Отключить", tunnelStickyCtaLabel(ConnState.PausedTrustedWifi))

        assertFalse(tunnelStickyCtaIsDestructive(ConnState.Probing))
        assertFalse(tunnelStickyCtaIsDestructive(ConnState.Idle))
        assertFalse(tunnelStickyCtaIsDestructive(ConnState.Ready))
        assertFalse(tunnelStickyCtaIsDestructive(ConnState.Error))
        assertFalse(tunnelStickyCtaIsDestructive(ConnState.Disconnecting))
        assertTrue(tunnelStickyCtaIsDestructive(ConnState.Connecting))
        assertTrue(tunnelStickyCtaIsDestructive(ConnState.Connected))
        assertTrue(tunnelStickyCtaIsDestructive(ConnState.PausedTrustedWifi))

        assertTrue(tunnelStickyCtaEnabled(ConnState.Probing, connectEnabled = false))
        assertFalse(tunnelStickyCtaEnabled(ConnState.Idle, connectEnabled = false))
        assertTrue(tunnelStickyCtaEnabled(ConnState.Idle, connectEnabled = true))
        assertTrue(tunnelStickyCtaEnabled(ConnState.Connecting, connectEnabled = false))
        assertFalse(tunnelStickyCtaEnabled(ConnState.Disconnecting, connectEnabled = true))
    }

    @Test
    fun userPowerToggleDoesNotLightDuringIdleProbe() {
        assertFalse(tunnelPowerBusy(ConnState.Probing))
        assertFalse(tunnelPowerSessionLit(ConnState.Probing))
        assertFalse(tunnelPowerToggleEnabled(ConnState.Probing, connectEnabled = false))
        assertFalse(tunnelPowerClickDisconnects(ConnState.Probing))

        assertTrue(tunnelPowerBusy(ConnState.Connecting))
        assertTrue(tunnelPowerSessionLit(ConnState.Connecting))
        assertTrue(tunnelPowerSessionLit(ConnState.Connected))
        assertTrue(tunnelPowerSessionLit(ConnState.Disconnecting))
        assertTrue(tunnelPowerClickDisconnects(ConnState.Connected))
        assertTrue(tunnelPowerToggleEnabled(ConnState.Connected, connectEnabled = false))
        assertTrue(tunnelPowerToggleEnabled(ConnState.Ready, connectEnabled = true))
        assertFalse(tunnelPowerToggleEnabled(ConnState.Ready, connectEnabled = false))
    }

    @Test
    fun tunnelParamsHiddenWhenQuickSettingsHidden() {
        assertFalse(tunnelConnectionParamsVisible(hideQuickSettings = true))
        assertTrue(tunnelConnectionParamsVisible(hideQuickSettings = false))
    }

    @Test
    fun tunnelQuickSettingsProfileHelpWhenEmptyOrLocked() {
        assertEquals("Нет сохранённых профилей.", tunnelQuickSettingsProfileHelp(0, locked = false))
        assertEquals("Нет сохранённых профилей.", tunnelQuickSettingsProfileHelp(0, locked = true))
        assertEquals(PROFILE_SWITCH_LOCKED_MESSAGE, tunnelQuickSettingsProfileHelp(2, locked = true))
        assertNull(tunnelQuickSettingsProfileHelp(1, locked = false))
        assertNull(tunnelQuickSettingsProfileHelp(2, locked = false))
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
            "В Wi‑Fi всегда прямое подключение. В мобильной сети — прямое, при недоступности обход.",
            PathModeCopy.help("auto", hasCallHash = false, compact = false),
        )
        assertEquals(
            "На Wi‑Fi — прямое. В мобильной сети сначала прямое, иначе обход.",
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
