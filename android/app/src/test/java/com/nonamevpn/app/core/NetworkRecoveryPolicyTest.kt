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
        assertEquals(12_000L, softRestartCooldownMs(12_000L, 0))
        assertEquals(20_000L, softRestartCooldownMs(12_000L, 1))
        assertEquals(44_000L, softRestartCooldownMs(12_000L, 4))
        assertEquals(44_000L, softRestartCooldownMs(12_000L, 20)) // count capped at 4
        assertTrue(shouldAttemptSoftRestartNow(100_000L, 0L, 12_000L, 0, force = false))
        assertFalse(shouldAttemptSoftRestartNow(20_000L, 15_000L, 12_000L, 0, force = false))
        assertTrue(shouldAttemptSoftRestartNow(20_000L, 15_000L, 12_000L, 0, force = true))
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
    fun trafficStallAfterHandoffUsesShorterGrace() {
        assertTrue(
            shouldSoftRestartForTrafficStall(
                activeWorkers = 3,
                trafficBytes = 1000L,
                lastTrafficGrowthAtMs = 80_000L,
                nowMs = 100_000L,
                handoffAtMs = 85_000L,
                afterHandoffGraceMs = 18_000L,
                idleGraceMs = 45_000L,
            ),
        )
        assertFalse(
            shouldSoftRestartForTrafficStall(
                activeWorkers = 3,
                trafficBytes = 1000L,
                lastTrafficGrowthAtMs = 90_000L,
                nowMs = 100_000L,
                handoffAtMs = 85_000L,
                afterHandoffGraceMs = 18_000L,
                idleGraceMs = 45_000L,
            ),
        )
        // Outside handoff window — need longer stall.
        assertFalse(
            shouldSoftRestartForTrafficStall(
                activeWorkers = 3,
                trafficBytes = 1000L,
                lastTrafficGrowthAtMs = 70_000L,
                nowMs = 100_000L,
                handoffAtMs = 0L,
                afterHandoffGraceMs = 18_000L,
                idleGraceMs = 45_000L,
            ),
        )
        assertTrue(
            shouldSoftRestartForTrafficStall(
                activeWorkers = 3,
                trafficBytes = 1000L,
                lastTrafficGrowthAtMs = 50_000L,
                nowMs = 100_000L,
                handoffAtMs = 0L,
                afterHandoffGraceMs = 18_000L,
                idleGraceMs = 45_000L,
            ),
        )
        assertFalse(
            shouldSoftRestartForTrafficStall(
                activeWorkers = 0,
                trafficBytes = 1000L,
                lastTrafficGrowthAtMs = 50_000L,
                nowMs = 100_000L,
                handoffAtMs = 90_000L,
            ),
        )
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

    @Test
    fun parseTrafficFromStatsLine() {
        val line =
            "[СТАТИСТИКА] Активных: 3 | Трафик: 29.30 МБ | ↓24.89 МБ / ↑4.41 МБ"
        val kb = TransportHealth.parseTrafficKb(line)
        assertTrue(kb != null && kb!! > 29_000L)
    }

    @Test
    fun initialValidatedAfterGraceTriggersHandoverCheck() {
        assertTrue(
            shouldTreatInitialValidatedAsHandover(
                tunnelRunning = true,
                userStopRequested = false,
                softRestartInProgress = false,
                sessionStartedAtMs = 1_000L,
                nowMs = 10_000L,
                graceAfterStartMs = 5_000L,
            ),
        )
        assertFalse(
            shouldTreatInitialValidatedAsHandover(
                tunnelRunning = true,
                userStopRequested = false,
                softRestartInProgress = false,
                sessionStartedAtMs = 8_000L,
                nowMs = 10_000L,
                graceAfterStartMs = 5_000L,
            ),
        )
        assertFalse(
            shouldTreatInitialValidatedAsHandover(
                tunnelRunning = true,
                userStopRequested = false,
                softRestartInProgress = true,
                sessionStartedAtMs = 1_000L,
                nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun handoverAutoSwitchesPathWhenProbeDisagrees() {
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Direct),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Bypass),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                currentPathHealthy = false,
                underlayVpsReachable = false,
            ),
        )
    }

    @Test
    fun handoverDoesNotKillHealthyDirectWhenUnderlayMissesVps() {
        assertEquals(
            NetworkHandoverDecision.NoAction,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                currentPathHealthy = true,
                underlayVpsReachable = false,
            ),
        )
    }

    @Test
    fun handoverIgnoresEventsDuringGrace() {
        assertEquals(
            NetworkHandoverDecision.NoAction,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                sessionAgeMs = 3_000L,
                currentPathHealthy = true,
                underlayVpsReachable = false,
            ),
        )
    }

    @Test
    fun handoverKeepsPathWhenForcedModeOrSameOrNoHash() {
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Direct,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = false,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = null,
                bypassAllowed = true,
            ),
        )
    }
}
