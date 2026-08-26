package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryPolicyTest {

    @Test
    fun validatedTransitionDetectsHandoverAfterLoss() {
        assertEquals(
            ValidatedNetworkTransition.HANDOVER,
            classifyValidatedNetworkTransition(
                previousNetworkId = 1L,
                currentNetworkId = 2L,
                previousNetworkWasLost = true,
            ),
        )
        assertEquals(
            ValidatedNetworkTransition.HANDOVER,
            classifyValidatedNetworkTransition(
                previousNetworkId = 1L,
                currentNetworkId = 2L,
                previousNetworkWasLost = false,
            ),
        )
        assertEquals(
            ValidatedNetworkTransition.INITIAL,
            classifyValidatedNetworkTransition(
                previousNetworkId = null,
                currentNetworkId = 2L,
                previousNetworkWasLost = false,
            ),
        )
        assertEquals(
            ValidatedNetworkTransition.UNCHANGED,
            classifyValidatedNetworkTransition(
                previousNetworkId = 2L,
                currentNetworkId = 2L,
                previousNetworkWasLost = false,
            ),
        )
    }

    @Test
    fun availableHandoverRequiresPriorLossAndANewNetwork() {
        assertTrue(shouldScheduleAvailableNetworkHandover(true, 1))
        assertFalse(shouldScheduleAvailableNetworkHandover(true, 0))
        assertFalse(shouldScheduleAvailableNetworkHandover(false, 2))
    }

    @Test
    fun reconnectGatesOnRunningSessionAndLiveNetwork() {
        assertTrue(
            shouldRunUnderlyingNetworkReconnect(
                tunnelRunning = true,
                userStopRequested = false,
                softRestartInProgress = false,
                realNetworkAvailable = true,
            ),
        )
        assertFalse(
            shouldRunUnderlyingNetworkReconnect(
                tunnelRunning = true,
                userStopRequested = true,
                softRestartInProgress = false,
                realNetworkAvailable = true,
            ),
        )
        assertFalse(
            shouldRunUnderlyingNetworkReconnect(
                tunnelRunning = true,
                userStopRequested = false,
                softRestartInProgress = true,
                realNetworkAvailable = true,
            ),
        )
        assertFalse(
            shouldRunUnderlyingNetworkReconnect(
                tunnelRunning = false,
                userStopRequested = false,
                softRestartInProgress = false,
                realNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun softRestartCooldownBacksOff() {
        assertEquals(45_000L, softRestartCooldownMs(45_000L, 0))
        assertEquals(60_000L, softRestartCooldownMs(45_000L, 1))
        assertEquals(105_000L, softRestartCooldownMs(45_000L, 4))
        assertEquals(105_000L, softRestartCooldownMs(45_000L, 20)) // count capped at 4
        assertTrue(shouldAttemptSoftRestartNow(100_000L, 0L, 45_000L, 0, force = false))
        assertFalse(shouldAttemptSoftRestartNow(50_000L, 40_000L, 45_000L, 0, force = false))
        assertTrue(shouldAttemptSoftRestartNow(50_000L, 40_000L, 45_000L, 0, force = true))
    }
}
