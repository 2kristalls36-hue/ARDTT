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
    fun autoFollowsProbeWhenWhitelistOff() {
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
                forceBypass = false,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
                forceBypass = false,
            ),
        )
    }

    @Test
    fun autoUsesBypassWhenAppWhitelistPinsRaw() {
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
                forceBypass = true,
            ),
        )
    }

    @Test
    fun forcedModesIgnoreWhitelistPin() {
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Direct,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
                forceBypass = true,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Bypass,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
                forceBypass = false,
            ),
        )
    }

    @Test
    fun appWhitelistForcesBypassRequiresAppsAndHash() {
        assertTrue(appWhitelistForcesBypass(true, 1, true))
        assertFalse(appWhitelistForcesBypass(true, 0, true))
        assertFalse(appWhitelistForcesBypass(false, 3, true))
        assertFalse(appWhitelistForcesBypass(true, 2, false))
    }
}
