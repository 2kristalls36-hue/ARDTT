package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePathSwitchTest {
    @Test
    fun autoOnDirectThenBypassSwitchesWhenHashPresent() {
        assertEquals(
            VpnPath.Bypass,
            resolveLiveSwitchPath(
                mode = ConnPathMode.Bypass,
                currentPath = VpnPath.Direct,
                probePath = VpnPath.Direct,
                hasCallHash = true,
            ),
        )
        assertTrue(livePathRestartRequired(VpnPath.Direct, VpnPath.Bypass))
    }

    @Test
    fun bypassWithoutHashDoesNotSwitch() {
        assertNull(
            resolveLiveSwitchPath(
                mode = ConnPathMode.Bypass,
                currentPath = VpnPath.Direct,
                probePath = VpnPath.Direct,
                hasCallHash = false,
            ),
        )
    }

    @Test
    fun forcedDirectAlwaysDirect() {
        assertEquals(
            VpnPath.Direct,
            resolveLiveSwitchPath(
                mode = ConnPathMode.Direct,
                currentPath = VpnPath.Bypass,
                probePath = VpnPath.Bypass,
                hasCallHash = true,
            ),
        )
        assertTrue(livePathRestartRequired(VpnPath.Bypass, VpnPath.Direct))
    }

    @Test
    fun autoUsesProbeWhenLeavingForcedPath() {
        assertEquals(
            VpnPath.Direct,
            resolveLiveSwitchPath(
                mode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probePath = VpnPath.Direct,
                hasCallHash = true,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveLiveSwitchPath(
                mode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probePath = VpnPath.Bypass,
                hasCallHash = true,
            ),
        )
    }

    @Test
    fun autoFallsBackToCurrentWhenProbeMissing() {
        assertEquals(
            VpnPath.Direct,
            resolveLiveSwitchPath(
                mode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probePath = null,
                hasCallHash = true,
            ),
        )
        assertFalse(livePathRestartRequired(VpnPath.Direct, VpnPath.Direct))
    }

    @Test
    fun autoProbeBypassWithoutHashStaysDirect() {
        assertEquals(
            VpnPath.Direct,
            resolveLiveSwitchPath(
                mode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probePath = VpnPath.Bypass,
                hasCallHash = false,
            ),
        )
    }

    @Test
    fun autoPinnedByAppWhitelistUsesBypassEvenIfProbeIsDirect() {
        assertEquals(
            VpnPath.Bypass,
            resolveLiveSwitchPath(
                mode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probePath = VpnPath.Direct,
                hasCallHash = true,
                forceBypass = true,
            ),
        )
    }
}
