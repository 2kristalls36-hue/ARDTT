package com.ardtt.app.core

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionControlIntentTest {
    @Test
    fun updateNetworkAloneDoesNotAllowNetOps() {
        val onlyHandle = Intent().putExtra(VpnTunnelService.EXTRA_NETWORK_HANDLE, 9L)
        assertNull(sessionControlNetOpsDelta(onlyHandle))
        val allow = Intent().putExtra(VpnTunnelService.EXTRA_NET_OPS_ALLOWED, true)
        assertEquals(true, sessionControlNetOpsDelta(allow))
        val forbid = Intent().putExtra(VpnTunnelService.EXTRA_NET_OPS_ALLOWED, false)
        assertEquals(false, sessionControlNetOpsDelta(forbid))
    }

    @Test
    fun parkedRawStaysOnCellularWhenDirectUsesWifi() {
        val target = goBypassNetworkTarget(
            activePath = VpnPath.Direct,
            transport = TransportLifecycle.Running,
            parkedRawAlive = true,
            underlayHandle = 22L,
            underlayKind = UnderlayKind.Wifi,
            cellularHandle = 11L,
        )
        assertEquals(11L, target?.handle)
        assertEquals(UnderlayKind.Cellular.name, target?.kind)
        assertEquals(VpnTunnelService.NETWORK_SCOPE_PARKED, target?.scope)
    }

    @Test
    fun liveBypassOnWifiKeepsCellularWhenBothExist() {
        val target = goBypassNetworkTarget(
            activePath = VpnPath.Bypass,
            transport = TransportLifecycle.Running,
            parkedRawAlive = true,
            underlayHandle = 22L,
            underlayKind = UnderlayKind.Wifi,
            cellularHandle = 11L,
        )
        assertEquals(11L, target?.handle)
        assertEquals(VpnTunnelService.NETWORK_SCOPE_ACTIVE, target?.scope)
    }

    @Test
    fun parkedRawWithoutCellularIsNotMovedToWifi() {
        assertNull(
            goBypassNetworkTarget(
                activePath = VpnPath.Direct,
                transport = TransportLifecycle.Running,
                parkedRawAlive = true,
                underlayHandle = 22L,
                underlayKind = UnderlayKind.Wifi,
                cellularHandle = null,
            ),
        )
    }
}
