package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellularHandoverProbeTest {
    @Test
    fun anUnmeasuredCellIsProbedInsteadOfGuessed() {
        assertTrue(
            shouldProbeCellularBeforeHandover(
                mode = ConnPathMode.Auto,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
                hasScopedEvidence = false,
            ),
        )
    }

    @Test
    fun aMeasuredCellSkipsTheBlockingRound() {
        assertFalse(
            shouldProbeCellularBeforeHandover(
                mode = ConnPathMode.Auto,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
                hasScopedEvidence = true,
            ),
        )
    }

    @Test
    fun withoutABypassOptionTheProbeBuysNothing() {
        assertFalse(
            shouldProbeCellularBeforeHandover(
                mode = ConnPathMode.Auto,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = false,
                hasScopedEvidence = false,
            ),
        )
        assertFalse(
            shouldProbeCellularBeforeHandover(
                mode = ConnPathMode.Direct,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
                hasScopedEvidence = false,
            ),
        )
        // Stubbed transports never carry a whitelist verdict.
        assertFalse(
            shouldProbeCellularBeforeHandover(
                mode = ConnPathMode.Auto,
                underlayKind = UnderlayKind.Wifi,
                bypassAllowed = true,
                hasScopedEvidence = false,
            ),
        )
    }

    @Test
    fun oneCleanRoundReachesTheEnterThreshold() {
        val score = RestrictionScore.apply(0, RestrictionSample.Positive)
        assertTrue(RestrictionScore.likely(score, alreadyBypass = false))
    }

    @Test
    fun aPartialRoundCannotFlipConnectOrHandoverToBypass() {
        val partial = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
            googleOutcome = CheckOutcome.Timeout,
            ruServiceOutcome = CheckOutcome.Cancelled,
        )
        assertEquals(25, partial.whitelistScorePercent)
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = partial.preselectedPath,
                lastGood = null,
                fresh = partial,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
                whitelistScorePercent = partial.whitelistScorePercent,
            ),
        )
        // measuredWhitelistLikely() at handover reads the same score.
        assertFalse(
            RestrictionScore.likely(partial.whitelistScorePercent, alreadyBypass = false),
        )
        assertFalse(
            RestrictionScore.likely(partial.whitelistScorePercent, alreadyBypass = true),
        )
    }
}

class ConnectPathTest {
    private val directOk = ProbeResult(
        networkClass = NetworkClass.DirectOk,
        preselectedPath = VpnPath.Direct,
        systemOnline = true,
        yandexOk = true,
        bigtechOk = true,
        captive = false,
        awgUdpOk = true,
        provisionOk = true,
        message = "Готово: прямое",
        elapsedMs = 10,
    )
    private val needBypass = ProbeResult(
        networkClass = NetworkClass.NeedBypass,
        preselectedPath = VpnPath.Bypass,
        systemOnline = true,
        yandexOk = true,
        bigtechOk = false,
        captive = false,
        awgUdpOk = false,
        provisionOk = false,
        message = "Готово: обход",
        elapsedMs = 10,
    )

    @Test
    fun autoFollowsProbe() {
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
                directFailedOnCurrentUnderlay = true,
            ),
        )
    }

    @Test
    fun forcedModesIgnoreProbe() {
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Direct,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Bypass,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
            ),
        )
    }

    @Test
    fun autoOnCellularFollowsDirectProbe() {
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass.copy(
                    restriction = RestrictionHint.Confirmed,
                    whitelistScorePercent = 80,
                ),
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
                whitelistScorePercent = 80,
            ),
        )
    }

    @Test
    fun autoOnWifiIgnoresNeedBypassProbe() {
        assertTrue(autoUsesDirectOnWifi(ConnPathMode.Auto, UnderlayKind.Wifi))
        assertFalse(autoUsesDirectOnWifi(ConnPathMode.Auto, UnderlayKind.Cellular))
        assertFalse(autoUsesDirectOnWifi(ConnPathMode.Bypass, UnderlayKind.Wifi))
        assertFalse(autoMayUseBypass(ConnPathMode.Auto, UnderlayKind.Wifi, hasCallHash = true))
        assertTrue(autoMayUseBypass(ConnPathMode.Auto, UnderlayKind.Cellular, hasCallHash = true))
        assertFalse(autoMayUseBypass(ConnPathMode.Auto, UnderlayKind.Cellular, hasCallHash = false))
        assertFalse(autoMayUseBypass(ConnPathMode.Direct, UnderlayKind.Cellular, hasCallHash = true))
        assertFalse(autoMayUseBypass(ConnPathMode.Auto, UnderlayKind.Other, hasCallHash = true))
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Bypass,
                lastGood = needBypass,
                fresh = needBypass,
                underlayKind = UnderlayKind.Wifi,
                bypassAllowed = true,
            ),
        )
        assertEquals(
            VpnPath.Bypass,
            resolveConnectPath(
                mode = ConnPathMode.Bypass,
                probePreferred = VpnPath.Direct,
                lastGood = directOk,
                fresh = directOk,
                underlayKind = UnderlayKind.Wifi,
                bypassAllowed = true,
            ),
        )
        val shown = displayedAutoProbe(ConnPathMode.Auto, UnderlayKind.Wifi, needBypass)
        assertEquals(VpnPath.Direct, shown.preselectedPath)
        assertEquals(NetworkClass.DirectOk, shown.networkClass)
        assertEquals(
            VpnPath.Bypass,
            displayedAutoProbe(ConnPathMode.Auto, UnderlayKind.Cellular, needBypass).preselectedPath,
        )
    }

    @Test
    fun skipConnectProbeOnlyForForcedBypass() {
        assertFalse(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Auto,
                bypassAllowed = true,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
        assertFalse(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Auto,
                bypassAllowed = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertTrue(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Bypass,
                bypassAllowed = true,
                underlayKind = UnderlayKind.Wifi,
            ),
        )
        assertFalse(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Direct,
                bypassAllowed = true,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
        assertFalse(
            shouldSkipConnectProbe(
                pathMode = ConnPathMode.Bypass,
                bypassAllowed = false,
                underlayKind = UnderlayKind.Cellular,
            ),
        )
    }

    @Test
    fun coldStartAutoOnCellularNeedsProbe() {
        assertTrue(
            connectNeedsInitialProbe(
                mode = ConnPathMode.Auto,
                probePreferred = null,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
        assertFalse(
            connectNeedsInitialProbe(
                mode = ConnPathMode.Auto,
                probePreferred = null,
                underlayKind = UnderlayKind.Wifi,
                bypassAllowed = true,
            ),
        )
        assertFalse(
            connectNeedsInitialProbe(
                mode = ConnPathMode.Auto,
                probePreferred = VpnPath.Direct,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
        assertFalse(
            connectNeedsInitialProbe(
                mode = ConnPathMode.Direct,
                probePreferred = null,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
            ),
        )
        assertTrue(
            connectNeedsInitialProbe(
                mode = ConnPathMode.Auto,
                probePreferred = null,
                underlayKind = UnderlayKind.Cellular,
                bypassAllowed = true,
                underlayUsable = true,
            ),
        )
        assertTrue(shouldStartDirectWithoutDiagnostic(ConnPathMode.Auto, UnderlayKind.Cellular, true))
        assertFalse(shouldStartDirectWithoutDiagnostic(ConnPathMode.Auto, UnderlayKind.Cellular, false))
        assertFalse(shouldStartDirectWithoutDiagnostic(ConnPathMode.Bypass, UnderlayKind.Cellular, true))
        assertTrue(
            connectSnapshotChanged(
                ConnPathMode.Auto,
                ConnPathMode.Bypass,
                UnderlayKind.Cellular,
                UnderlayKind.Cellular,
                "p",
                "p",
            ),
        )
        assertFalse(
            connectSnapshotChanged(
                ConnPathMode.Auto,
                ConnPathMode.Auto,
                UnderlayKind.Cellular,
                UnderlayKind.Cellular,
                "p",
                "p",
            ),
        )
    }

    @Test
    fun noNetworkWithUsableUnderlayStillStartsDirect() {
        val none = ProbeResult(
            networkClass = NetworkClass.NoNetwork,
            preselectedPath = null,
            systemOnline = false,
            yandexOk = false,
            bigtechOk = false,
            captive = false,
            provisionOk = false,
            message = "Нет сети",
            elapsedMs = 10,
        )
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = null,
                lastGood = null,
                fresh = none,
                underlayKind = UnderlayKind.Cellular,
                underlayUsable = true,
            ),
        )
        assertEquals(
            VpnPath.Direct,
            resolveConnectPath(
                mode = ConnPathMode.Auto,
                probePreferred = null,
                lastGood = null,
                fresh = none.copy(
                    networkClass = NetworkClass.DataUnconfirmed,
                    preselectedPath = VpnPath.Direct,
                    message = "Передача данных не подтверждена",
                ),
                underlayKind = UnderlayKind.Cellular,
            ),
        )
    }

    @Test
    fun autoCellularWaitsForWhitelistProbeUntilEvidenceExists() {
        assertTrue(
            shouldWaitForCellularWhitelistProbe(
                mode = ConnPathMode.Auto,
                underlayKind = UnderlayKind.Cellular,
                state = ConnState.Idle,
                hasSameNetworkProbeEvidence = false,
            ),
        )
        assertTrue(
            shouldWaitForCellularWhitelistProbe(
                mode = ConnPathMode.Auto,
                underlayKind = UnderlayKind.Cellular,
                state = ConnState.Probing,
                hasSameNetworkProbeEvidence = true,
            ),
        )
        assertFalse(
            shouldWaitForCellularWhitelistProbe(
                mode = ConnPathMode.Auto,
                underlayKind = UnderlayKind.Cellular,
                state = ConnState.Ready,
                hasSameNetworkProbeEvidence = true,
            ),
        )
        assertFalse(
            shouldWaitForCellularWhitelistProbe(
                mode = ConnPathMode.Auto,
                underlayKind = UnderlayKind.Wifi,
                state = ConnState.Idle,
                hasSameNetworkProbeEvidence = false,
            ),
        )
    }
}
