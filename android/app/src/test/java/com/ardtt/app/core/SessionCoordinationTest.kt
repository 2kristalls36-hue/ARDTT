package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoordinationTest {
    @Test
    fun mergePendingHandoverKeepsLatestIdentityAndStickyChange() {
        val first = PendingHandoverEvent(
            reason = "wifi-lost",
            underlayChanged = true,
            previousNetworkId = 1L,
            currentNetworkId = 2L,
            evidenceSinceMs = 10L,
            generation = 1L,
            rebuildTun = true,
        )
        val second = PendingHandoverEvent(
            reason = "sim-swap",
            underlayChanged = false,
            previousNetworkId = null,
            currentNetworkId = 3L,
            evidenceSinceMs = 40L,
            generation = 2L,
            rebuildTun = false,
        )
        val merged = mergePendingHandover(first, second)
        assertEquals("sim-swap", merged.reason)
        assertTrue(merged.underlayChanged)
        assertEquals(1L, merged.previousNetworkId)
        assertEquals(3L, merged.currentNetworkId)
        assertEquals(40L, merged.evidenceSinceMs)
        assertEquals(2L, merged.generation)
        assertTrue(merged.rebuildTun)
    }

    @Test
    fun staleHandoverProbeDoesNotApplyAfterManualMode() {
        assertFalse(
            shouldApplyHandoverProbe(
                snapshotMode = ConnPathMode.Auto,
                liveMode = ConnPathMode.Direct,
                snapshotPath = VpnPath.Direct,
                livePath = VpnPath.Direct,
            ),
        )
        assertTrue(
            shouldApplyHandoverProbe(
                snapshotMode = ConnPathMode.Auto,
                liveMode = ConnPathMode.Auto,
                snapshotPath = VpnPath.Direct,
                livePath = VpnPath.Direct,
            ),
        )
    }

    @Test
    fun cancelledProbeJoinDoesNotConnect() {
        assertFalse(shouldConnectAfterProbeJoin(ConnState.Disconnecting))
        assertFalse(shouldConnectAfterProbeJoin(ConnState.Connected))
        assertFalse(shouldConnectAfterProbeJoin(ConnState.Connecting))
        assertTrue(shouldConnectAfterProbeJoin(ConnState.Ready))
        assertTrue(shouldConnectAfterProbeJoin(ConnState.Idle))
    }

    @Test
    fun oldJobFinallyDoesNotClearNewSoftRestart() {
        assertFalse(shouldClearSoftRestartFlag(handedOff = false, jobEpoch = 1L, currentEpoch = 2L))
        assertTrue(shouldClearSoftRestartFlag(handedOff = false, jobEpoch = 2L, currentEpoch = 2L))
        assertFalse(shouldClearSoftRestartFlag(handedOff = true, jobEpoch = 2L, currentEpoch = 2L))
    }

    @Test
    fun trustedWifiResumeProbesAutoOnCellular() {
        assertTrue(trustedWifiResumeNeedsProbe(ConnPathMode.Auto, UnderlayKind.Cellular))
        assertFalse(trustedWifiResumeNeedsProbe(ConnPathMode.Auto, UnderlayKind.Wifi))
        assertFalse(trustedWifiResumeNeedsProbe(ConnPathMode.Direct, UnderlayKind.Cellular))
        assertEquals(
            VpnPath.Direct,
            resolveTrustedWifiResumePath(
                mode = ConnPathMode.Auto,
                savedPath = VpnPath.Bypass,
                probePath = VpnPath.Bypass,
                hasCallHash = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertEquals(
            VpnPath.Direct,
            resolveTrustedWifiResumePath(
                mode = ConnPathMode.Direct,
                savedPath = VpnPath.Bypass,
                probePath = VpnPath.Bypass,
                hasCallHash = true,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
    }

    @Test
    fun validatedChosenNetworkIgnoresOtherUnderlays() {
        assertTrue(isValidatedUnderlay(hasInternet = true, notVpn = true, validated = true))
        assertFalse(isValidatedUnderlay(hasInternet = true, notVpn = true, validated = false))
        assertFalse(isValidatedUnderlay(hasInternet = true, notVpn = false, validated = true))
    }
}
