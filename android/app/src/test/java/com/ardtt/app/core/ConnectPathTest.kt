package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectPathTest {
    private val directOk = ProbeResult(
        networkClass = NetworkClass.DirectOk,
        preselectedPath = VpnPath.Direct,
        systemOnline = true,
        yandexOk = true,
        bigtechOk = true,
        captive = false,
        awgUdpOk = true,
        provisionOk = true,
        message = "Готово: прямое",
        elapsedMs = 10,
    )
    private val needBypass = ProbeResult(
        networkClass = NetworkClass.NeedBypass,
        preselectedPath = VpnPath.Bypass,
        systemOnline = true,
        yandexOk = true,
        bigtechOk = false,
        captive = false,
        awgUdpOk = false,
        provisionOk = false,
        message = "Готово: обход",
        elapsedMs = 10,
    )

    @Test
    fun autoFollowsProbe() {
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
            ),
        )
    }

    @Test
    fun forcedModesIgnoreProbe() {
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Direct,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Bypass,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
            ),
        )
    }

    @Test
    fun autoOnCellularFollowsDirectProbe() {
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
    }

    @Test
    fun autoOnWifiIgnoresNeedBypassProbe() {
        assertTrue(autoUsesDirectOnWifi(ConnPathMode.Auto, UnderlayKind.Wifi))
        assertFalse(autoUsesDirectOnWifi(ConnPathMode.Auto, UnderlayKind.Cellular))
        assertFalse(autoUsesDirectOnWifi(ConnPathMode.Bypass, UnderlayKind.Wifi))
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
                underlayKind = UnderlayKind.Wifi,
                bypassAllowed = true,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Bypass,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
                underlayKind = UnderlayKind.Wifi,
                bypassAllowed = true,
            ),
        )
        val shown = displayedAutoProbe(ConnPathMode.Auto, UnderlayKind.Wifi, needBypass)
        assertEquals(VpnPath.Direct, shown.preselectedPath)
        assertEquals(NetworkClass.DirectOk, shown.networkClass)
        assertEquals(
            VpnPath.Bypass,
            displayedAutoProbe(ConnPathMode.Auto, UnderlayKind.Cellular, needBypass).preselectedPath,
        )
    }

    @Test
    fun skipConnectProbeOnlyForForcedBypass() {
        assertFalse(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Auto,
                bypassAllowed = true,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
        assertFalse(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Auto,
                bypassAllowed = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertTrue(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Bypass,
                bypassAllowed = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertFalse(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Direct,
                bypassAllowed = true,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
        assertFalse(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Bypass,
                bypassAllowed = false,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
    }

    @Test
    fun coldStartAutoOnCellularNeedsProbe() {
        assertTrue(
            connectNeedsInitialProbe(
                mode = ConnPathMode.Auto,
                probePreferred = null,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
        assertFalse(
            connectNeedsInitialProbe(
                mode = ConnPathMode.Auto,
                probePreferred = null,
                underlayKind = UnderlayKind.Wifi,
                bypassAllowed = true,
            ),
        )
        assertFalse(
            connectNeedsInitialProbe(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Direct,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
        assertFalse(
            connectNeedsInitialProbe(
                mode = ConnPathMode.Direct,
                probePreferred = null,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
    }
}
