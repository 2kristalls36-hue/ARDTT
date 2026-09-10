package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlIntentTest {
    @Test
    fun updateNetworkAloneDoesNotAllowNetOps() {
        assertNull(sessionControlNetOpsDelta(hasNetOpsExtra = false, allowed = true))
        assertNull(sessionControlNetOpsDelta(hasNetOpsExtra = false, allowed = false))
        assertEquals(true, sessionControlNetOpsDelta(hasNetOpsExtra = true, allowed = true))
        assertEquals(false, sessionControlNetOpsDelta(hasNetOpsExtra = true, allowed = false))
        assertEquals(false, sessionControlShouldDiscardParked(hasDiscardExtra = false, discard = true))
        assertEquals(false, sessionControlShouldDiscardParked(hasDiscardExtra = true, discard = false))
        assertEquals(true, sessionControlShouldDiscardParked(hasDiscardExtra = true, discard = true))
        assertTrue(
            sessionControlShouldApplyDiscard(
                hasDiscardExtra = true,
                discard = true,
                extraCallEpoch = 4L,
                liveCallEpoch = 4L,
            ),
        )
        assertTrue(
            !sessionControlShouldApplyDiscard(
                hasDiscardExtra = true,
                discard = true,
                extraCallEpoch = 3L,
                liveCallEpoch = 5L,
            ),
        )
        assertTrue(
            sessionControlShouldApplyDiscard(
                hasDiscardExtra = true,
                discard = true,
                extraCallEpoch = null,
                liveCallEpoch = 5L,
            ),
        )
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
