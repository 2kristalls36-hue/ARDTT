package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedWifiTest {
    @Test
    fun enterWaitingOnTrustedSsidWhileRunning() {
        assertEquals(
            TrustedWifiTransition.EnterWaiting,
            decideTrustedWifiTransition(
                enabled = true,
                tunnelRunning = true,
                waiting = false,
                wifi = ConnectedWifiState(connected = true, ssid = "Home"),
                trustedSsids = setOf("Home"),
            ),
        )
    }

    @Test
    fun resumeWhenLeavingTrustedSsid() {
        assertEquals(
            TrustedWifiTransition.ResumeVpn,
            decideTrustedWifiTransition(
                enabled = true,
                tunnelRunning = false,
                waiting = true,
                wifi = ConnectedWifiState(connected = true, ssid = "Cafe"),
                trustedSsids = setOf("Home"),
            ),
        )
        assertEquals(
            TrustedWifiTransition.ResumeVpn,
            decideTrustedWifiTransition(
                enabled = true,
                tunnelRunning = false,
                waiting = true,
                wifi = ConnectedWifiState(connected = false),
                trustedSsids = setOf("Home"),
            ),
        )
    }

    @Test
    fun stayWaitingWhileStillOnTrusted() {
        assertEquals(
            TrustedWifiTransition.None,
            decideTrustedWifiTransition(
                enabled = true,
                tunnelRunning = false,
                waiting = true,
                wifi = ConnectedWifiState(connected = true, ssid = "Home"),
                trustedSsids = setOf("Home"),
            ),
        )
    }

    @Test
    fun sanitizeStripsQuotesAndControls() {
        assertEquals("Home", sanitizeTrustedWifiSsid("\"Home\""))
        assertEquals("", sanitizeTrustedWifiSsid("   "))
        assertTrue(sanitizeTrustedWifiSsid("a".repeat(100)).toByteArray().size <= 32)
    }

    @Test
    fun disabledFeatureResumesWhenWaiting() {
        assertEquals(
            TrustedWifiTransition.None,
            decideTrustedWifiTransition(
                enabled = false,
                tunnelRunning = true,
                waiting = false,
                wifi = ConnectedWifiState(connected = true, ssid = "Home"),
                trustedSsids = setOf("Home"),
            ),
        )
        assertEquals(
            TrustedWifiTransition.ResumeVpn,
            decideTrustedWifiTransition(
                enabled = false,
                tunnelRunning = false,
                waiting = true,
                wifi = ConnectedWifiState(connected = true, ssid = "Home"),
                trustedSsids = setOf("Home"),
            ),
        )
        assertEquals(
            TrustedWifiTransition.ResumeVpn,
            decideTrustedWifiTransition(
                enabled = true,
                tunnelRunning = false,
                waiting = true,
                wifi = ConnectedWifiState(connected = true, ssid = "Home"),
                trustedSsids = emptySet(),
            ),
        )
        assertFalse(
            decideTrustedWifiTransition(
                enabled = true,
                tunnelRunning = true,
                waiting = false,
                wifi = ConnectedWifiState(connected = true, ssid = "Home"),
                trustedSsids = emptySet(),
            ) == TrustedWifiTransition.EnterWaiting,
        )
    }

    @Test
    fun stayWaitingWhenSsidTemporarilyUnavailable() {
        assertEquals(
            TrustedWifiTransition.None,
            decideTrustedWifiTransition(
                enabled = true,
                tunnelRunning = false,
                waiting = true,
                wifi = ConnectedWifiState(
                    connected = true,
                    accessProblem = TrustedWifiAccessProblem.ForegroundPermission,
                ),
                trustedSsids = setOf("Home"),
            ),
        )
    }

    @Test
    fun trustedSsidMatchIsCaseInsensitive() {
        assertEquals(
            TrustedWifiTransition.EnterWaiting,
            decideTrustedWifiTransition(
                enabled = true,
                tunnelRunning = true,
                waiting = false,
                wifi = ConnectedWifiState(connected = true, ssid = "HomeWiFi"),
                trustedSsids = setOf("homewifi"),
            ),
        )
        assertTrue(isTrustedSsid("Cafe", setOf("cafe")))
    }

    @Test
    fun handoverGateWaitsThenHoldsWhenSsidUnknown() {
        val wifi = ConnectedWifiState(connected = true, ssid = "")
        val ssids = setOf("Home")
        assertEquals(
            TrustedWifiHandoverGate.WaitForSsid,
            decideTrustedWifiHandoverGate(true, ssids, wifi, waitedMs = 1_000L),
        )
        assertEquals(
            TrustedWifiHandoverGate.HoldPath,
            decideTrustedWifiHandoverGate(true, ssids, wifi, waitedMs = 8_000L),
        )
    }

    @Test
    fun handoverGateHoldsImmediatelyWhenPermissionMissing() {
        assertEquals(
            TrustedWifiHandoverGate.HoldPath,
            decideTrustedWifiHandoverGate(
                true,
                setOf("Home"),
                ConnectedWifiState(
                    connected = true,
                    accessProblem = TrustedWifiAccessProblem.ForegroundPermission,
                ),
                waitedMs = 0L,
            ),
        )
    }

    @Test
    fun handoverGatePausesTrustedAndProbesUntrusted() {
        val ssids = setOf("Home")
        assertEquals(
            TrustedWifiHandoverGate.PauseVpn,
            decideTrustedWifiHandoverGate(
                true,
                ssids,
                ConnectedWifiState(connected = true, ssid = "Home"),
                waitedMs = 0L,
            ),
        )
        assertEquals(
            TrustedWifiHandoverGate.Proceed,
            decideTrustedWifiHandoverGate(
                true,
                ssids,
                ConnectedWifiState(connected = true, ssid = "Cafe"),
                waitedMs = 0L,
            ),
        )
    }

    @Test
    fun handoverGateDisabledOrNoWifiProceeds() {
        val ssids = setOf("Home")
        assertEquals(
            TrustedWifiHandoverGate.Proceed,
            decideTrustedWifiHandoverGate(
                false,
                ssids,
                ConnectedWifiState(connected = true, ssid = ""),
                waitedMs = 0L,
            ),
        )
        assertEquals(
            TrustedWifiHandoverGate.Proceed,
            decideTrustedWifiHandoverGate(
                true,
                ssids,
                ConnectedWifiState(connected = false),
                waitedMs = 0L,
            ),
        )
        assertEquals(
            TrustedWifiHandoverGate.Proceed,
            decideTrustedWifiHandoverGate(
                true,
                emptySet(),
                ConnectedWifiState(connected = true, ssid = ""),
                waitedMs = 0L,
            ),
        )
    }
}
