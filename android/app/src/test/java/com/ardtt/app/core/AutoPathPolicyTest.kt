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
    fun ghostWifiKindOnCellularKeepsBypassInsteadOfWifiDirect() {
        val key = NetworkKey(1L, UnderlayKind.Wifi, null, "ghost")
        val cellKey = NetworkKey(3L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = UnderlaySnapshot(
                    key = key,
                    kind = UnderlayKind.Wifi,
                    availability = UnderlayAvailability.Usable,
                    handle = 1L,
                    wifiConnected = false,
                    cellularConnected = true,
                    networkEpoch = 1L,
                ),
                evidence = ReachabilityEvidence(
                    networkKey = cellKey,
                    originNetworkKey = cellKey,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.Timeout,
                    ruService = CheckOutcome.Success,
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 100,
                ).withFreshStrongTtl(),
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), d)
        assertFalse(d is AutoDecision.StartDirect)
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
    fun cellularTriesDirectWhenWhitelistScoreIsWeak() {
        val underlay = cellular()
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = underlay,
                evidence = ReachabilityEvidence(
                    networkKey = underlay.key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.NotRun,
                    provision = CheckOutcome.NotRun,
                    restriction = RestrictionHint.Suspected,
                    whitelistScorePercent = 25,
                    measuredAtElapsedMs = 1L,
                    usableAtElapsedMs = 1L,
                    ttlUntilElapsedMs = 40_000L,
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
    fun cellularStartsBypassWhenWhitelistScoreIsHigh() {
        val underlay = cellular()
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = underlay,
                evidence = ReachabilityEvidence(
                    networkKey = underlay.key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.Timeout,
                    ruService = CheckOutcome.Success,
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 80,
                ).withFreshStrongTtl(),
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
            ),
        )
        val bypass = d as AutoDecision.StartBypass
        assertEquals("cellular-whitelist", bypass.reason)
        assertTrue(bypass.reuseCall)
    }

    @Test
    fun afterDirectFailsWithoutWhitelistRetriesDirect() {
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
        val start = d as AutoDecision.StartDirect
        assertEquals("cellular-direct", start.reason)
        assertTrue(start.keepCall)
    }

    @Test
    fun afterDirectFailsWithWhitelistUsesExistingCall() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.Timeout,
                    ruService = CheckOutcome.Success,
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 80,
                ).withFreshStrongTtl(),
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
        assertEquals("cellular-direct-failed", bypass.reason)
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
                    networkKey = key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Success,
                    provision = CheckOutcome.Timeout,
                    restriction = RestrictionHint.None,
                    measuredAtElapsedMs = 1L,
                    usableAtElapsedMs = 1L,
                    ttlUntilElapsedMs = 40_000L,
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
    fun lteHandleFlapStillBlocksDirectWhileBypassRuns() {
        val first = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell-a", carrier = "25002")
        val flapped = NetworkKey(99L, UnderlayKind.Cellular, 7, "cell-b", carrier = "25002")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = flapped),
                evidence = ReachabilityEvidence(
                    networkKey = flapped,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Success,
                    provision = CheckOutcome.Success,
                    restriction = RestrictionHint.None,
                ),
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
                directNegative = DirectNegativeEvidence(
                    key = first,
                    failedAtElapsedMs = 0L,
                    retryAfterElapsedMs = 120_000L,
                ),
                elapsedMs = 5_000L,
                reevalDue = true,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), d)
        assertTrue(
            DirectNegativeEvidence(
                key = first,
                retryAfterElapsedMs = 120_000L,
            ).stillBlocks(5_000L, flapped, null),
        )
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
        val stayWhitelisted = decideAutoPath(
            input.copy(
                reevalDue = true,
                evidence = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.Timeout,
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 80,
                ),
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), stayWhitelisted)
    }

    @Test
    fun wifiDirectIsNotKeptOnCellularWhenWhitelistScoreIsHigh() {
        val cellKey = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val wifiKey = NetworkKey(2L, UnderlayKind.Wifi, null, "wifi")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = cellKey),
                evidence = ReachabilityEvidence(
                    networkKey = cellKey,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.Timeout,
                    ruService = CheckOutcome.Success,
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 80,
                ).withFreshStrongTtl(),
                currentPath = VpnPath.Direct,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                lastConfirmedNetworkKey = wifiKey,
            ),
        )
        val bypass = d as AutoDecision.StartBypass
        assertEquals("cellular-whitelist", bypass.reason)
    }

    @Test
    fun historicalWhitelistScoreDoesNotTearDownWorkingDirect() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.Timeout,
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 80,
                ),
                currentPath = VpnPath.Direct,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                lastConfirmedNetworkKey = key,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Direct, "direct-works"), d)
    }

    @Test
    fun freshWhitelistOnLiveCellularDirectStartsBypass() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.Timeout,
                    ruService = CheckOutcome.Success,
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 80,
                ).withFreshStrongTtl(),
                currentPath = VpnPath.Direct,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                lastConfirmedNetworkKey = key,
            ),
        )
        val bypass = d as AutoDecision.StartBypass
        assertEquals("cellular-whitelist", bypass.reason)
        assertTrue(bypass.reuseCall)
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

    @Test
    fun wifiUpgradeSettleWindowWidensWithTheFailStreak() {
        val settle = RecoverySettings.wifiUpgradeSettleMs(0)
        assertTrue(
            wifiUpgradeStillSettling(
                wifiUsableSinceMs = 10_000L,
                wifiFailStreak = 0,
                elapsedMs = 10_000L + settle - 1L,
            ),
        )
        assertFalse(
            wifiUpgradeStillSettling(
                wifiUsableSinceMs = 10_000L,
                wifiFailStreak = 0,
                elapsedMs = 10_000L + settle,
            ),
        )
        // A failed episode buys the access point a longer window.
        assertTrue(
            wifiUpgradeStillSettling(
                wifiUsableSinceMs = 10_000L,
                wifiFailStreak = 3,
                elapsedMs = 10_000L + settle,
            ),
        )
        // Unknown "usable since": nothing to wait on.
        assertFalse(
            wifiUpgradeStillSettling(
                wifiUsableSinceMs = 0L,
                wifiFailStreak = 3,
                elapsedMs = 10_000L,
            ),
        )
    }

    @Test
    fun staleWhitelistScoreDoesNotStartBypassOnANewConnect() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val stale = ReachabilityEvidence(
            networkKey = key,
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Timeout,
            google = CheckOutcome.Timeout,
            ruService = CheckOutcome.Success,
            restriction = RestrictionHint.Confirmed,
            whitelistScorePercent = 80,
            measuredAtElapsedMs = 10L,
            usableAtElapsedMs = 10L,
            strongAtElapsedMs = 10L,
            ttlUntilElapsedMs = 10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS,
            strongUntilElapsedMs = 10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS,
        )
        val expiredAt = 10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = stale,
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
                elapsedMs = expiredAt,
                profileId = null,
            ),
        )
        assertTrue(d is AutoDecision.StartDirect)
    }

    @Test
    fun liveBypassDoesNotSwitchOnTtlExpiryAlone() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val expiredAt = 10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS
        val stale = ReachabilityEvidence(
            networkKey = key,
            whitelistScorePercent = 80,
            restriction = RestrictionHint.Confirmed,
            measuredAtElapsedMs = 10L,
            usableAtElapsedMs = 10L,
            strongAtElapsedMs = 10L,
            ttlUntilElapsedMs = expiredAt,
            strongUntilElapsedMs = expiredAt,
            unknownStreak = 1,
        )
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = stale,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                elapsedMs = expiredAt,
                reevalDue = true,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), d)
    }

    @Test
    fun fourUnknownRoundsAllowAControlledDirectReeval() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val expiredAt = 10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS
        val stale = ReachabilityEvidence(
            networkKey = key,
            whitelistScorePercent = 80,
            restriction = RestrictionHint.Confirmed,
            measuredAtElapsedMs = 10L,
            usableAtElapsedMs = 10L,
            strongAtElapsedMs = 10L,
            ttlUntilElapsedMs = expiredAt,
            strongUntilElapsedMs = expiredAt,
            unknownStreak = RecoverySettings.WHITELIST_UNKNOWN_DIRECT_TRY_STREAK,
        )
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = stale,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                elapsedMs = expiredAt,
                reevalDue = true,
            ),
        )
        assertTrue(d is AutoDecision.StartDirect)
        assertEquals("reeval-direct", (d as AutoDecision.StartDirect).reason)
    }

    @Test
    fun twoOpenRoundsAllowDirectReevalOnLiveBypass() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val afterTwoOpen = ReachabilityEvidence(
            networkKey = key,
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Success,
            google = CheckOutcome.Success,
            restriction = RestrictionHint.None,
            whitelistScorePercent = 44,
        ).withFreshStrongTtl(atElapsedMs = 10L).copy(
            strongAtElapsedMs = 0L,
            strongUntilElapsedMs = 0L,
            usableAtElapsedMs = 10L,
            ttlUntilElapsedMs = 10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS,
        )
        val stay = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = afterTwoOpen,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                elapsedMs = 20L,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), stay)
        val reeval = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = afterTwoOpen,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                elapsedMs = 20L,
                reevalDue = true,
            ),
        )
        assertEquals("reeval-direct", (reeval as AutoDecision.StartDirect).reason)
        assertTrue(reeval.keepCall)
    }

    @Test
    fun freshWhitelistOnLiveBypassDoesNotReevalDirect() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.Timeout,
                    ruService = CheckOutcome.Success,
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 80,
                ).withFreshStrongTtl(),
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                elapsedMs = 20L,
                reevalDue = true,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), d)
    }

    @Test
    fun oneOpenRoundDoesNotSwitchLiveBypass() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val afterOneOpen = ReachabilityEvidence(
            networkKey = key,
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Success,
            google = CheckOutcome.Success,
            restriction = RestrictionHint.Suspected,
            whitelistScorePercent = 62,
        ).withFreshStrongTtl(atElapsedMs = 10L)
        val d = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = cellular(key = key),
                evidence = afterOneOpen,
                currentPath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                hasCallHash = true,
                elapsedMs = 20L,
                reevalDue = true,
            ),
        )
        assertEquals(AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), d)
    }

    @Test
    fun manualDirectAndCaptiveAndTrustedStayOnExistingRules() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val manual = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Direct,
                underlay = cellular(key = key),
                evidence = ReachabilityEvidence(
                    networkKey = key,
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 80,
                ).withFreshStrongTtl(),
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
            ),
        )
        assertEquals("manual-direct", (manual as AutoDecision.StartDirect).reason)
        val captive = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = wifi(UnderlayAvailability.Captive, cellularAlso = false),
                evidence = null,
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = false,
            ),
        )
        assertEquals(AutoDecision.ShowCaptive, captive)
    }

    @Test
    fun staleExitRangeScoreHoldsLiveBypassOnReeval() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val expiredAt = 10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS
        for (score in listOf(55, 62, 64, 79)) {
            val d = decideAutoPath(
                AutoPathInput(
                    mode = ConnPathMode.Auto,
                    underlay = cellular(key = key),
                    evidence = ReachabilityEvidence(
                        networkKey = key,
                        whitelistScorePercent = score,
                        restriction = RestrictionHint.Suspected,
                        measuredAtElapsedMs = 10L,
                        usableAtElapsedMs = 10L,
                        strongAtElapsedMs = 10L,
                        ttlUntilElapsedMs = expiredAt,
                        strongUntilElapsedMs = expiredAt,
                        unknownStreak = 1,
                    ),
                    currentPath = VpnPath.Bypass,
                    transport = TransportLifecycle.Running,
                    hasCallHash = true,
                    elapsedMs = expiredAt,
                    reevalDue = true,
                ),
            )
            assertEquals("score $score", AutoDecision.Stay(VpnPath.Bypass, "bypass-running"), d)
        }
    }
}
