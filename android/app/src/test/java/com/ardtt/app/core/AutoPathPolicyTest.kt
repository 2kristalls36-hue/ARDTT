package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPathPolicyTest {
    private fun cellular(
        availability: UnderlayAvailability = UnderlayAvailability.Usable,
        key: NetworkKey = NetworkKey(1L, UnderlayKind.Cellular, simId = 7, configFingerprint = "cell"),
    ) = UnderlaySnapshot(
        key = key,
        kind = UnderlayKind.Cellular,
        availability = availability,
        handle = key.handle,
        simId = key.simId,
        cellularConnected = availability != UnderlayAvailability.None,
        capabilitiesComplete = availability != UnderlayAvailability.Incomplete,
        notSuspended = availability != UnderlayAvailability.Suspended,
        networkEpoch = 1L,
    )

    private fun wifi(
        availability: UnderlayAvailability = UnderlayAvailability.Usable,
        cellularAlso: Boolean = true,
    ) = UnderlaySnapshot(
        key = NetworkKey(2L, UnderlayKind.Wifi, simId = null, configFingerprint = "wifi"),
        kind = UnderlayKind.Wifi,
        availability = availability,
        handle = 2L,
        wifiConnected = true,
        cellularConnected = cellularAlso,
        captivePortal = availability == UnderlayAvailability.Captive,
        networkEpoch = 2L,
    )

    @Test
    fun noUnderlayWaitsWithoutPaths() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = UnderlaySnapshot(),
                evidence = null,
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
            ),
        )
        assertEquals(AutoDecision.WaitForUnderlay, d)
    }

    @Test
    fun suspendedCellularWaits() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(UnderlayAvailability.Suspended),
                evidence = null,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Paused,
                hasCallHash = true,
            ),
        )
        assertEquals(AutoDecision.WaitSuspended, d)
    }

    @Test
    fun unknownKindDoesNotAssumeCellular() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = UnderlaySnapshot(
                    kind = UnderlayKind.Other,
                    availability = UnderlayAvailability.Usable,
                    capabilitiesComplete = true,
                ),
                evidence = null,
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
            ),
        )
        assertEquals(AutoDecision.WaitUnknownKind, d)
    }

    @Test
    fun wifiStartsDirectImmediatelyAndKeepsCall() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = wifi(),
                evidence = null,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
            ),
        )
        val start = d as AutoDecision.StartDirect
        assertTrue(start.keepCall)
        assertTrue(start.immediate)
        assertEquals("wifi-direct", start.reason)
    }

    @Test
    fun cellularDirectWinsEvenIfCloudflareFailed() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    provision = CheckOutcome.Success,
                    restriction = RestrictionHint.Suspected,
                ),
                currentPath = VpnPath.Direct,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Direct, "direct-works"), d)
    }

    @Test
    fun cellularTriesDirectBeforeBypassWhenRestricted() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(),
                evidence = ReachabilityEvidence(
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    provision = CheckOutcome.NotRun,
                    restriction = RestrictionHint.Suspected,
                ),
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
            ),
        )
        val start = d as AutoDecision.StartDirect
        assertEquals("cellular-try-direct", start.reason)
        assertTrue(start.keepCall)
    }

    @Test
    fun afterDirectFailsUsesExistingCall() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = ReachabilityEvidence(restriction = RestrictionHint.Suspected),
                currentPath = VpnPath.Direct,
                transport = TransportLifecycle.Failed,
                hasCallHash = true,
                call = CallSessionState(hashPresent = true),
                directNegative = DirectNegativeEvidence(
                    key = key,
                    retryAfterElapsedMs = 60_000L,
                ),
            ),
        )
        val bypass = d as AutoDecision.StartBypass
        assertTrue(bypass.reuseCall)
    }

    @Test
    fun healthFailureIsNotWhitelistWhenInternetWorks() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = ReachabilityEvidence(
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Success,
                    provision = CheckOutcome.Timeout,
                    restriction = RestrictionHint.None,
                ),
                currentPath = VpnPath.Direct,
                transport = TransportLifecycle.Failed,
                hasCallHash = false,
                directNegative = DirectNegativeEvidence(
                    key = key,
                    retryAfterElapsedMs = 60_000L,
                ),
            ),
        )
        assertTrue(d is AutoDecision.ServerFault)
    }

    @Test
    fun ethernetUsesDirectWithoutMobileDiagnosis() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = UnderlaySnapshot(
                    key = NetworkKey(9L, UnderlayKind.Ethernet, null, "eth"),
                    kind = UnderlayKind.Ethernet,
                    availability = UnderlayAvailability.Usable,
                    ethernetConnected = true,
                ),
                evidence = null,
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
            ),
        )
        val start = d as AutoDecision.StartDirect
        assertTrue(start.immediate)
    }

    @Test
    fun captiveWifiKeepsMobileBypass() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = wifi(UnderlayAvailability.Captive, cellularAlso = true),
                evidence = null,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "captive-wifi-keep-mobile"), d)
    }

    @Test
    fun wifiHysteresisKeepsMobileAfterRepeatedFails() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = wifi(),
                evidence = null,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                wifiFailStreak = 2,
                wifiStableHits = 0,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "wifi-hysteresis"), d)
        val reeval = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = wifi(),
                evidence = null,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                wifiFailStreak = 2,
                wifiStableHits = 0,
                reevalDue = true,
            ),
        )
        assertEquals(
            AutoDecision.StartDirect(
                keepCall = true,
                immediate = false,
                reason = "wifi-hysteresis-reeval",
            ),
            reeval,
        )
    }

    @Test
    fun cacheDoesNotOverrideNoUnderlay() {
        val cache = ReachabilityEvidence(
            networkKey = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell"),
            profileId = "p",
            ttlUntilElapsedMs = 50_000L,
            provision = CheckOutcome.Success,
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Success,
        )
        assertFalse(
            cacheAllowsProbeSkip(
                cache = cache,
                key = cache.networkKey,
                profileId = "p",
                elapsedMs = 10L,
                availability = UnderlayAvailability.None,
                userStop = false,
            ),
        )
        assertTrue(
            cacheAllowsProbeSkip(
                cache = cache,
                key = cache.networkKey,
                profileId = "p",
                elapsedMs = 10L,
                availability = UnderlayAvailability.Usable,
                userStop = false,
            ),
        )
    }

    @Test
    fun handoverWifiFromBypassSwitchesImmediately() {
        val decision = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = wifi(),
                evidence = null,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
            ),
        ).toHandoverDecision(VpnPath.Bypass)
        assertEquals(NetworkHandoverDecision.SwitchPath(VpnPath.Direct), decision)
    }

    @Test
    fun failedWifiOnNewLteTriesDirectAndKeepsParkedCall() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(),
                evidence = null,
                currentPath = VpnPath.Direct,
                transport = TransportLifecycle.Failed,
                hasCallHash = true,
                wifiFailStreak = 1,
                parkedRawAlive = true,
            ),
        )
        val start = d as AutoDecision.StartDirect
        assertTrue(start.keepCall)
        assertEquals("cellular-direct", start.reason)
    }

    @Test
    fun degradedWifiStillConnectedReturnsToParkedMobile() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = wifi(),
                evidence = null,
                currentPath = VpnPath.Direct,
                transport = TransportLifecycle.Failed,
                hasCallHash = true,
                wifiFailStreak = 2,
                parkedRawAlive = true,
            ),
        )
        val bypass = d as AutoDecision.StartBypass
        assertTrue(bypass.reuseCall)
        assertEquals("wifi-failed-return-mobile", bypass.reason)
    }

    @Test
    fun unexpiredDirectEvidenceKeepsWorkingBypass() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = null,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
                directNegative = DirectNegativeEvidence(
                    key = key,
                    failedAtElapsedMs = 0L,
                    retryAfterElapsedMs = 10_000L,
                ),
                elapsedMs = 1_000L,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), d)
    }

    @Test
    fun expiredDirectEvidenceKeepsBypassUntilReevalTimer() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val input = AutoPathInput(
            mode = ConnPathMode.Auto,
            underlay = cellular(key = key),
            evidence = null,
            currentPath = VpnPath.Bypass,
            transport = TransportLifecycle.Running,
            hasCallHash = true,
            call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
            directNegative = DirectNegativeEvidence(
                key = key,
                failedAtElapsedMs = 0L,
                retryAfterElapsedMs = 1_000L,
            ),
            elapsedMs = 10_000L,
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), decideAutoPath(input))
        val start = decideAutoPath(input.copy(reevalDue = true)) as AutoDecision.StartDirect
        assertEquals("reeval-direct", start.reason)
        assertTrue(start.keepCall)
    }

    @Test
    fun expiredDirectEvidenceReevaluatesDirectWithoutDroppingCall() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = null,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
                directNegative = DirectNegativeEvidence(
                    key = key,
                    failedAtElapsedMs = 0L,
                    retryAfterElapsedMs = 1_000L,
                ),
                elapsedMs = 10_000L,
                reevalDue = true,
            ),
        )
        val start = d as AutoDecision.StartDirect
        assertEquals("reeval-direct", start.reason)
        assertTrue(start.keepCall)
    }

    @Test
    fun expiredDirectEvidenceRetriesDirectWhenIdle() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = null,
                currentPath = VpnPath.Direct,
                transport = TransportLifecycle.Stopped,
                hasCallHash = false,
                directNegative = DirectNegativeEvidence(
                    key = key,
                    failedAtElapsedMs = 0L,
                    retryAfterElapsedMs = 1_000L,
                ),
                elapsedMs = 10_000L,
            ),
        )
        val start = d as AutoDecision.StartDirect
        assertEquals("cellular-direct", start.reason)
    }

    @Test
    fun startingBypassIsNotYankedByDirectHint() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(),
                evidence = ReachabilityEvidence(
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Success,
                    provision = CheckOutcome.Success,
                    restriction = RestrictionHint.None,
                ),
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Starting,
                hasCallHash = true,
                call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-try-in-flight"), d)
    }

    @Test
    fun deadBypassCallDoesNotBlockAutoDirect() {
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(),
                evidence = null,
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
                call = CallSessionState(
                    hashPresent = true,
                    validity = CallValidity.ConfirmedDead,
                ),
            ),
        )
        val start = d as AutoDecision.StartDirect
        assertTrue(start.keepCall)
    }
}
