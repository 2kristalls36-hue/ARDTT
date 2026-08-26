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
    fun disabledFeatureDoesNothingUnlessWaitingWithEmptyList() {
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
}
