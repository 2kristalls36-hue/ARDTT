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

    @Test
    fun wakeRescueReconnectsWhenBypassHasNoWorkers() {
        assertTrue(
            shouldReconnectTunnelAfterWake(
                activeWorkers = 0,
                hasFreshStatsSinceWake = false,
                bypassPath = true,
                backendAlive = true,
            ),
        )
        assertFalse(
            shouldReconnectTunnelAfterWake(
                activeWorkers = 2,
                hasFreshStatsSinceWake = true,
                bypassPath = true,
                backendAlive = true,
            ),
        )
        assertTrue(
            shouldReconnectTunnelAfterWake(
                activeWorkers = 0,
                hasFreshStatsSinceWake = false,
                bypassPath = false,
                backendAlive = false,
            ),
        )
    }

    @Test
    fun zeroWorkersGraceRequiresSustainedZero() {
        assertFalse(shouldSoftRestartForZeroWorkers(0, 0L, 100_000L))
        assertFalse(shouldSoftRestartForZeroWorkers(0, 90_000L, 100_000L, graceMs = 60_000L))
        assertTrue(shouldSoftRestartForZeroWorkers(0, 30_000L, 100_000L, graceMs = 60_000L))
        assertFalse(shouldSoftRestartForZeroWorkers(3, 30_000L, 100_000L, graceMs = 60_000L))
    }

    @Test
    fun healthObserveGatesOnInteractiveAndGrace() {
        assertTrue(
            shouldObserveTunnelHealth(
                deviceInteractive = true,
                wakeRecoveryGraceActive = false,
                trustedWifiWaiting = false,
                softRestartInProgress = false,
            ),
        )
        assertFalse(
            shouldObserveTunnelHealth(
                deviceInteractive = false,
                wakeRecoveryGraceActive = false,
                trustedWifiWaiting = false,
                softRestartInProgress = false,
            ),
        )
        assertFalse(
            shouldObserveTunnelHealth(
                deviceInteractive = true,
                wakeRecoveryGraceActive = true,
                trustedWifiWaiting = false,
                softRestartInProgress = false,
            ),
        )
    }
}
