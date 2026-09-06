package com.ardtt.app.core

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
        assertEquals(
            ValidatedNetworkTransition.HANDOVER,
            classifyValidatedNetworkTransition(
                previousNetworkId = 2L,
                currentNetworkId = 2L,
                previousNetworkWasLost = false,
                dataSubscriptionChanged = true,
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
        assertEquals(4_000L, softRestartCooldownMs(4_000L, 0))
        assertEquals(6_000L, softRestartCooldownMs(4_000L, 1))
        assertEquals(10_000L, softRestartCooldownMs(4_000L, 3))
        assertEquals(10_000L, softRestartCooldownMs(4_000L, 20))
        assertEquals(18_000L, softRestartCooldownMs(12_000L, 4))
        assertEquals(15_000L, softRestartCooldownMs(12_000L, 4, maxMs = 15_000L))
        assertTrue(shouldAttemptSoftRestartNow(100_000L, 0L, 4_000L, 0, force = false))
        assertFalse(shouldAttemptSoftRestartNow(20_000L, 17_000L, 4_000L, 0, force = false))
        assertTrue(shouldAttemptSoftRestartNow(20_000L, 17_000L, 4_000L, 0, force = true))
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
        assertTrue(
            shouldReconnectTunnelAfterWake(
                activeWorkers = 0,
                hasFreshStatsSinceWake = false,
                bypassPath = false,
                backendAlive = true,
                directEgressOk = false,
            ),
        )
        assertFalse(
            shouldReconnectTunnelAfterWake(
                activeWorkers = 0,
                hasFreshStatsSinceWake = false,
                bypassPath = false,
                backendAlive = true,
                directEgressOk = true,
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
    fun directEgressWatchdogIgnoresWakeGrace() {
        assertTrue(
            shouldObserveDirectEgress(
                tunnelRunning = true,
                userStopRequested = false,
                softRestartInProgress = false,
            ),
        )
        assertFalse(
            shouldObserveDirectEgress(
                tunnelRunning = true,
                userStopRequested = false,
                softRestartInProgress = true,
            ),
        )
        assertFalse(
            shouldObserveDirectEgress(
                tunnelRunning = false,
                userStopRequested = false,
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
    fun handoverAutoSwitchesToBypassOnWhitelist() {
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Bypass),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                currentPathHealthy = true,
                underlayVpsReachable = false,
                sameProbeStreak = 2,
            ),
        )
        // TCP :9100 up is not AWG UDP — whitelist still leaves Direct.
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Bypass),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                currentPathHealthy = true,
                underlayVpsReachable = true,
                sameProbeStreak = 2,
            ),
        )
    }

    @Test
    fun handoverBypassToDirectNeedsUnderlayChangeNotStreak() {
        assertEquals(
            NetworkHandoverDecision.NoAction,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                sameProbeStreak = 1,
            ),
        )
        // Stall / ghost TCP :9100 — two DirectOk hits must not yank Bypass.
        assertEquals(
            NetworkHandoverDecision.NoAction,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                sameProbeStreak = 2,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Direct),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                sameProbeStreak = 1,
                underlayChanged = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
    }

    @Test
    fun updateProbeStreakCountsConsecutivePaths() {
        val first = updateProbeStreak(ProbeStreak(), VpnPath.Direct)
        assertEquals(ProbeStreak(VpnPath.Direct, 1), first)
        val second = updateProbeStreak(first, VpnPath.Direct)
        assertEquals(ProbeStreak(VpnPath.Direct, 2), second)
        val switched = updateProbeStreak(second, VpnPath.Bypass)
        assertEquals(ProbeStreak(VpnPath.Bypass, 1), switched)
        assertEquals(ProbeStreak(), updateProbeStreak(switched, null))
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
    fun handoverGraceRebindsWhenUnderlayActuallyChanged() {
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                sessionAgeMs = 3_000L,
                currentPathHealthy = true,
                underlayVpsReachable = true,
                underlayChanged = true,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.NoAction,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                sessionAgeMs = 3_000L,
                currentPathHealthy = true,
                underlayVpsReachable = true,
                underlayChanged = false,
            ),
        )
    }

    @Test
    fun handoverDirectToBypassOnUnderlayChangeFirstNeedBypass() {
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                currentPathHealthy = true,
                underlayVpsReachable = false,
                sameProbeStreak = 1,
                underlayChanged = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                currentPathHealthy = true,
                underlayVpsReachable = false,
                sameProbeStreak = 1,
                underlayChanged = false,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Bypass),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                currentPathHealthy = true,
                underlayVpsReachable = false,
                sameProbeStreak = 2,
                underlayChanged = false,
            ),
        )
    }

    @Test
    fun handoverUnderlayChangeRebindsBypassInsteadOfWaiting() {
        assertEquals(
            NetworkHandoverDecision.NoAction,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                sameProbeStreak = 1,
                underlayChanged = false,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Direct),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                sameProbeStreak = 1,
                underlayChanged = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Direct),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                underlayVpsReachable = false,
                sameProbeStreak = 1,
                underlayChanged = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Direct),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                underlayVpsReachable = false,
                sameProbeStreak = 1,
                underlayChanged = false,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
    }

    @Test
    fun confirmedUnderlayChangeUsesLossOrNewNetworkId() {
        assertTrue(isConfirmedUnderlayChange(previousNetworkWasLost = true, previousNetworkId = null))
        assertTrue(isConfirmedUnderlayChange(previousNetworkWasLost = false, previousNetworkId = 7L))
        assertFalse(isConfirmedUnderlayChange(previousNetworkWasLost = false, previousNetworkId = null))
    }

    @Test
    fun handoverKeepsPathWhenForcedModeOrSameOrNoHash() {
        assertEquals(
            NetworkHandoverDecision.NoAction,
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
                pathMode = ConnPathMode.Direct,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                underlayChanged = true,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.NoAction,
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
            NetworkHandoverDecision.HoldWaitForNetwork,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = null,
                bypassAllowed = true,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.HoldWaitForNetwork,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Direct,
                currentPath = VpnPath.Direct,
                probedPath = null,
                bypassAllowed = true,
            ),
        )
    }

    @Test
    fun overlappingHandoversAreDeferredNotDropped() {
        assertTrue(shouldDeferHandoverProbe(handoverProbeInProgress = true, softRestartInProgress = false))
        assertTrue(shouldDeferHandoverProbe(handoverProbeInProgress = false, softRestartInProgress = true))
        assertFalse(shouldDeferHandoverProbe(handoverProbeInProgress = false, softRestartInProgress = false))
    }

    @Test
    fun unvalidatedBypassSettleIsSubSecondForSimHandover() {
        assertTrue(BYPASS_UNVALIDATED_SETTLE_MS <= 500L)
        assertEquals(
            BYPASS_UNVALIDATED_SETTLE_MS,
            extraNetworkSettleDelayMs(VpnPath.Bypass, validatedPresent = false),
        )
    }

    @Test
    fun handoverDoesNotRestartStableWhitelistBypass() {
        assertEquals(
            NetworkHandoverDecision.NoAction,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                currentPathHealthy = false,
                underlayVpsReachable = false,
                underlayChanged = false,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                underlayVpsReachable = false,
                underlayChanged = true,
            ),
        )
    }

    @Test
    fun plusStyleTimingsWaitForValidatedAndSkipFreshTraffic() {
        val bypass = transportRecoveryPolicy(VpnPath.Bypass)
        val direct = transportRecoveryPolicy(VpnPath.Direct)
        assertEquals(BYPASS_NETWORK_SETTLE_MS, bypass.networkSettleDelayMs)
        assertEquals(BYPASS_RECONNECT_MIN_INTERVAL_MS, bypass.reconnectMinIntervalMs)
        assertEquals(DIRECT_NETWORK_SETTLE_MS, direct.networkSettleDelayMs)
        assertTrue(bypass.networkSettleDelayMs > 400L)
        assertTrue(bypass.reconnectMinIntervalMs > 4_000L)
        assertTrue(direct.networkSettleDelayMs < bypass.networkSettleDelayMs)

        assertTrue(shouldKeepWaitingForValidated(validatedPresent = false, waitedMs = 1_000L))
        assertFalse(shouldKeepWaitingForValidated(validatedPresent = true, waitedMs = 100L))
        assertFalse(
            shouldKeepWaitingForValidated(
                validatedPresent = false,
                waitedMs = VALIDATED_WAIT_TIMEOUT_MS,
            ),
        )
        assertEquals(80L, updatedUnderlyingNetworkEvidenceSince(50L, 80L))
        assertEquals(90L, updatedUnderlyingNetworkEvidenceSince(90L, 20L))

        assertFalse(
            shouldSkipHandoverRestartIfTrafficFresh(
                bypassTrafficFresh = true,
                directTrafficFresh = false,
                path = VpnPath.Bypass,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertTrue(
            shouldSkipHandoverRestartIfTrafficFresh(
                bypassTrafficFresh = true,
                directTrafficFresh = false,
                path = VpnPath.Bypass,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
        assertFalse(
            shouldSkipHandoverRestartIfTrafficFresh(
                bypassTrafficFresh = false,
                directTrafficFresh = true,
                path = VpnPath.Bypass,
            ),
        )
        assertFalse(
            shouldSkipHandoverRestartIfTrafficFresh(
                bypassTrafficFresh = true,
                directTrafficFresh = true,
                path = VpnPath.Bypass,
                validatedPresent = false,
            ),
        )
        assertFalse(
            shouldSkipHandoverRestartIfTrafficFresh(
                bypassTrafficFresh = false,
                directTrafficFresh = true,
                path = VpnPath.Direct,
            ),
        )
        assertFalse(
            shouldSkipHandoverRestartIfTrafficFresh(
                bypassTrafficFresh = true,
                directTrafficFresh = true,
                path = VpnPath.Direct,
            ),
        )
        assertEquals(
            VALIDATED_WAIT_WHEN_UNDERLAY_PRESENT_MS,
            validatedWaitTimeoutMs(replacementUnderlayPresent = true),
        )
        assertEquals(
            VALIDATED_WAIT_TIMEOUT_MS,
            validatedWaitTimeoutMs(replacementUnderlayPresent = false),
        )
        assertEquals(
            0L,
            validatedWaitTimeoutMs(
                replacementUnderlayPresent = true,
                skipWait = true,
            ),
        )
        assertFalse(shouldSkipValidatedWait(VpnPath.Bypass, UnderlayKind.Wifi))
        assertTrue(shouldSkipValidatedWait(VpnPath.Direct, UnderlayKind.Cellular))
        assertTrue(shouldSkipValidatedWait(VpnPath.Bypass, UnderlayKind.Cellular))
        assertFalse(shouldSkipValidatedWait(VpnPath.Direct, UnderlayKind.Wifi))
        assertEquals(
            BYPASS_UNVALIDATED_SETTLE_MS,
            extraNetworkSettleDelayMs(
                VpnPath.Direct,
                validatedPresent = false,
                skipValidatedWait = true,
            ),
        )
        assertEquals(
            BYPASS_UNVALIDATED_SETTLE_MS,
            extraNetworkSettleDelayMs(VpnPath.Bypass, validatedPresent = false),
        )
        assertEquals(
            BYPASS_NETWORK_SETTLE_MS,
            extraNetworkSettleDelayMs(VpnPath.Bypass, validatedPresent = true),
        )
        assertEquals(
            DIRECT_NETWORK_SETTLE_MS,
            extraNetworkSettleDelayMs(VpnPath.Direct, validatedPresent = false),
        )
        assertTrue(BYPASS_UNVALIDATED_SETTLE_MS < BYPASS_NETWORK_SETTLE_MS)
        assertFalse(
            shouldKeepWaitingForValidated(
                validatedPresent = false,
                waitedMs = VALIDATED_WAIT_WHEN_UNDERLAY_PRESENT_MS,
                timeoutMs = VALIDATED_WAIT_WHEN_UNDERLAY_PRESENT_MS,
            ),
        )
    }

    @Test
    fun deadDirectSwitchesAutoToBypassOtherwiseStops() {
        assertFalse(
            shouldTreatDirectAsDeadNoRx(
                nowMs = 3_500L,
                sessionStartedAtMs = 1_000L,
                lastHandoffAtMs = 0L,
                hasFreshRxSinceAnchor = false,
            ),
        )
        assertFalse(
            shouldTreatDirectAsDeadNoRx(
                nowMs = 50_000L,
                sessionStartedAtMs = 1_000L,
                lastHandoffAtMs = 0L,
                hasFreshRxSinceAnchor = true,
            ),
        )
        assertTrue(
            shouldTreatDirectAsDeadNoRx(
                nowMs = 50_000L,
                sessionStartedAtMs = 1_000L,
                lastHandoffAtMs = 0L,
                hasFreshRxSinceAnchor = false,
            ),
        )
        // After a handoff, 3s no-rx is enough (skip the cold-start grace).
        assertFalse(
            shouldTreatDirectAsDeadNoRx(
                nowMs = 14_000L,
                sessionStartedAtMs = 1_000L,
                lastHandoffAtMs = 12_000L,
                hasFreshRxSinceAnchor = false,
            ),
        )
        assertTrue(
            shouldTreatDirectAsDeadNoRx(
                nowMs = 16_000L,
                sessionStartedAtMs = 1_000L,
                lastHandoffAtMs = 12_000L,
                hasFreshRxSinceAnchor = false,
            ),
        )
        assertEquals(
            DeadDirectDecision.SwitchToBypass,
            decideDeadDirectAction(ConnPathMode.Auto, bypassAllowed = true),
        )
        assertEquals(
            DeadDirectDecision.FailSession,
            decideDeadDirectAction(
                ConnPathMode.Auto,
                bypassAllowed = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertEquals(
            DeadDirectDecision.FailSession,
            decideDeadDirectAction(ConnPathMode.Auto, bypassAllowed = false),
        )
        assertEquals(
            DeadDirectDecision.FailSession,
            decideDeadDirectAction(ConnPathMode.Direct, bypassAllowed = true),
        )
    }

    @Test
    fun stallProbeMustNotUpgradeBypassAfterDeadDirect() {
        assertEquals(
            NetworkHandoverDecision.NoAction,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                sameProbeStreak = 2,
                underlayChanged = false,
                allowBypassToDirect = false,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                sameProbeStreak = 1,
                underlayChanged = true,
                directFailedOnCurrentUnderlay = true,
            ),
        )
    }

    @Test
    fun cellularAutoKeepsDirectWhenVpsIsReachable() {
        assertEquals(UnderlayKind.Wifi, classifyUnderlayKind(wifi = true, cellular = true))
        assertEquals(UnderlayKind.Cellular, classifyUnderlayKind(wifi = false, cellular = true))

        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                underlayChanged = true,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Bypass),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                underlayVpsReachable = false,
                underlayChanged = true,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Bypass),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Direct,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                underlayVpsReachable = true,
                underlayChanged = true,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                underlayChanged = true,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
    }

    @Test
    fun handshakeStallRebindsQuietBypassAfterHandoff() {
        assertFalse(
            shouldSoftRestartForHandshakeStall(
                bypassPath = true,
                activeWorkers = 9,
                trafficKb = 90L,
                nowMs = 10_000L,
                handoffAtMs = 1_000L,
            ),
        )
        assertTrue(
            shouldSoftRestartForHandshakeStall(
                bypassPath = true,
                activeWorkers = 9,
                trafficKb = 110L,
                nowMs = 20_000L,
                handoffAtMs = 1_000L,
            ),
        )
        assertFalse(
            shouldSoftRestartForHandshakeStall(
                bypassPath = true,
                activeWorkers = 9,
                trafficKb = 5_000L,
                nowMs = 20_000L,
                handoffAtMs = 1_000L,
            ),
        )
        assertFalse(
            shouldSoftRestartForHandshakeStall(
                bypassPath = false,
                activeWorkers = 9,
                trafficKb = 110L,
                nowMs = 20_000L,
                handoffAtMs = 1_000L,
            ),
        )
    }

    @Test
    fun wifiUnderlaySwitchesBypassToDirectImmediately() {
        assertEquals(
            NetworkHandoverDecision.SwitchPath(VpnPath.Direct),
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Bypass,
                bypassAllowed = true,
                underlayVpsReachable = false,
                underlayChanged = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertEquals(
            NetworkHandoverDecision.SoftRestartSamePath,
            decideNetworkHandoverAction(
                pathMode = ConnPathMode.Auto,
                currentPath = VpnPath.Bypass,
                probedPath = VpnPath.Direct,
                bypassAllowed = true,
                underlayVpsReachable = true,
                underlayChanged = true,
                underlayKind = UnderlayKind.Wifi,
                directFailedOnCurrentUnderlay = true,
            ),
        )
    }

    @Test
    fun parkBypassCallOnlyWhenLeavingForDirect() {
        assertTrue(shouldParkBypassCall(VpnPath.Bypass, VpnPath.Direct))
        assertFalse(shouldParkBypassCall(VpnPath.Direct, VpnPath.Bypass))
        assertFalse(shouldParkBypassCall(VpnPath.Bypass, VpnPath.Bypass))
        assertEquals(UnderlayKind.Wifi, preferWifiUnderlayKind(true, UnderlayKind.Cellular))
        assertEquals(UnderlayKind.Cellular, preferWifiUnderlayKind(false, UnderlayKind.Cellular))
        assertEquals(
            UnderlayKind.Wifi,
            preferWifiUnderlayKind(false, UnderlayKind.Cellular, wifiConnected = true),
        )
    }
}
