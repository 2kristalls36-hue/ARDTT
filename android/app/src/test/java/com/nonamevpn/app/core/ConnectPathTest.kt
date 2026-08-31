package com.nonamevpn.app.core

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
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
                underlayKind = UnderlayKind.Wifi,
                bypassAllowed = true,
            ),
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
}
