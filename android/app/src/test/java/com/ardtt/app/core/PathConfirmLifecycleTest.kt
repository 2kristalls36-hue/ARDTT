package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for R1/R2 PathConfirm identity: unknown tunGen adoption,
 * exact RAW counters, Direct operation baseline, and foreign leftover rejection.
 */
class PathConfirmLifecycleTest {
    private val key = NetworkKey(1L, UnderlayKind.Cellular, simId = 1, configFingerprint = "c")

    @Before
    fun resetHealth() {
        TransportHealth.reset()
    }

    @Test
    fun unknownTunGenThenFirstTelemetryIsNotStale() {
        val started = TransportHealth.noteBackendStarted(callEpoch = 3L)
        assertEquals(-1L, started.tunGen)
        val baselineProcess = started.processId
        val baselineOp = started.operationId
        // Running arrives before GET_TELEMETRY; verifier holds unknown gen.
        var capturedTunGen = -1L
        var baselineOk = 0L
        var baselineDown = 0L

        TransportHealth.applyStructuredTelemetry(
            "channels=2|tunGen=1|tunWriteOk=3|tunWriteErr=0|down=40|up=10",
        )
        val snap = TransportHealth.snapshot()
        if (capturedTunGen < 0L && snap.tunGen >= 0L) {
            capturedTunGen = snap.tunGen
            baselineOk = snap.tunWriteOk
            baselineDown = snap.exactDownBytes
        }
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 3L,
            eventCallEpoch = 3L,
            capturedTunGen = capturedTunGen,
            eventTunGen = snap.tunGen,
            tunWriteOkDelta = (snap.tunWriteOk - baselineOk).coerceAtLeast(0L),
            tunWriteErrDelta = 0L,
            usefulRxDelta = (snap.exactDownBytes - baselineDown).coerceAtLeast(0L),
            workersPresent = snap.activeWorkers > 0,
            capturedProcessId = baselineProcess,
            eventProcessId = snap.processId,
            capturedOperationId = baselineOp,
            eventOperationId = snap.operationId,
        )
        assertEquals(PathConfirmVerdict.BackendRunning, PathConfirm.verdict(obs))

        TransportHealth.applyStructuredTelemetry(
            "channels=2|tunGen=1|tunWriteOk=5|tunWriteErr=0|down=90|up=10",
        )
        val later = TransportHealth.snapshot()
        val confirmed = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 3L,
            eventCallEpoch = 3L,
            capturedTunGen = capturedTunGen,
            eventTunGen = later.tunGen,
            tunWriteOkDelta = (later.tunWriteOk - baselineOk).coerceAtLeast(0L),
            tunWriteErrDelta = 0L,
            usefulRxDelta = (later.exactDownBytes - baselineDown).coerceAtLeast(0L),
            workersPresent = true,
            capturedProcessId = baselineProcess,
            eventProcessId = later.processId,
            capturedOperationId = baselineOp,
            eventOperationId = later.operationId,
        )
        assertEquals(PathConfirmVerdict.PathConfirmed, PathConfirm.verdict(confirmed))
    }

    @Test
    fun lateTelemetryFromPreviousProcessIsStale() {
        val first = TransportHealth.noteBackendStarted()
        TransportHealth.applyStructuredTelemetry("channels=1|tunGen=1|tunWriteOk=1|down=10|up=0")
        val second = TransportHealth.noteBackendStarted()
        assertNotEquals(first.processId, second.processId)
        TransportHealth.applyStructuredTelemetry("channels=1|tunGen=1|tunWriteOk=2|down=20|up=0")
        val snap = TransportHealth.snapshot()
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedTunGen = 1L,
            eventTunGen = snap.tunGen,
            tunWriteOkDelta = 1L,
            tunWriteErrDelta = 0L,
            usefulRxDelta = 10L,
            workersPresent = true,
            capturedProcessId = first.processId,
            eventProcessId = snap.processId,
            capturedOperationId = first.operationId,
            eventOperationId = snap.operationId,
        )
        assertEquals(PathConfirmVerdict.Stale, PathConfirm.verdict(obs))
    }

    @Test
    fun exactSmallPacketConfirmsWithoutRoundedLogBytes() {
        TransportHealth.noteBackendStarted()
        TransportHealth.applyStructuredTelemetry(
            "channels=1|tunGen=1|tunWriteOk=1|tunWriteErr=0|down=500|up=0",
        )
        // Rounded МБ log would still be 0.00; exact path must confirm.
        assertTrue(TransportHealth.exactDownBytes < 0.01 * 1024 * 1024)
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedTunGen = 1L,
            eventTunGen = 1L,
            tunWriteOkDelta = 1L,
            tunWriteErrDelta = 0L,
            usefulRxDelta = TransportHealth.exactDownBytes,
            workersPresent = true,
        )
        assertEquals(PathConfirmVerdict.PathConfirmed, PathConfirm.verdict(obs))
    }

    @Test
    fun workersAloneAreNotPathConfirmed() {
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedTunGen = 1L,
            eventTunGen = 1L,
            tunWriteOkDelta = 0L,
            tunWriteErrDelta = 0L,
            usefulRxDelta = 0L,
            workersPresent = true,
        )
        assertEquals(PathConfirmVerdict.BackendRunning, PathConfirm.verdict(obs))
        assertTrue(PathConfirm.bypassMayConnect(PathConfirm.verdict(obs)))
    }

    @Test
    fun directOperationBaselinePreservesEarlyHandshake() {
        val baseline = VpnLiveStats.beginDirectOperation()
        // Handshake completed before verifier sampled.
        val obs = PathConfirm.assembleDirect(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedHandle = 5L,
            eventHandle = 5L,
            handshakeBaselineSec = baseline.handshakeSecAtStart,
            handshakeNowSec = 4L,
            rxBaseline = baseline.rxAtStart,
            rxNow = 0L,
            source = PathConfirmSource.DirectAwg,
            capturedOperationId = baseline.operationId,
            eventOperationId = baseline.operationId,
        )
        assertEquals(PathConfirmVerdict.ProtocolReady, PathConfirm.verdict(obs))
    }

    @Test
    fun foreignOperationHandshakeDoesNotConfirm() {
        val a = VpnLiveStats.beginDirectOperation()
        val b = VpnLiveStats.beginDirectOperation()
        val obs = PathConfirm.assembleDirect(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedHandle = 9L,
            eventHandle = 9L,
            handshakeBaselineSec = 0L,
            handshakeNowSec = 20L,
            rxBaseline = 0L,
            rxNow = 100L,
            source = PathConfirmSource.DirectAwg,
            capturedOperationId = a.operationId,
            eventOperationId = b.operationId,
        )
        assertEquals(PathConfirmVerdict.Stale, PathConfirm.verdict(obs))
    }

    @Test
    fun directConfigRevisionDetectsSameNameDifferentKeys() {
        val p1 = sampleProfile(endpoint = "1.1.1.1:51820", peer = "AAA")
        val p2 = sampleProfile(endpoint = "1.1.1.1:51820", peer = "BBB")
        assertEquals(p1.name, p2.name)
        assertNotEquals(
            PathConfirm.directConfigRevision(p1),
            PathConfirm.directConfigRevision(p2),
        )
    }

    @Test
    fun openLteDiagnosticScheduleIsNotEveryEightSecondsForever() {
        val delay = RecoverySettings.nextDiagnosticDelayMs(
            completedSeries = 1,
            restriction = RestrictionHint.None,
            seriesCount = 0,
        )
        assertEquals(RecoverySettings.DIAGNOSTIC_OPEN_INTERVAL_MS, delay)
        assertTrue(delay!! > RecoverySettings.DIAGNOSTIC_SERIES_GAP_MS)
    }

    @Test
    fun restrictionRefreshUsesShorterInterval() {
        val delay = RecoverySettings.nextDiagnosticDelayMs(
            completedSeries = 2,
            restriction = RestrictionHint.Confirmed,
            seriesCount = 2,
        )
        assertEquals(RecoverySettings.DIAGNOSTIC_RESTRICTION_REFRESH_MS, delay)
    }

    private fun sampleProfile(
        endpoint: String,
        peer: String,
    ): com.ardtt.app.profile.VpnProfile =
        com.ardtt.app.profile.VpnProfile(
            name = "same",
            deviceId = "d",
            hostId = 1,
            direct = com.ardtt.app.profile.DirectConfig(
                endpoint = endpoint,
                privateKey = "priv",
                peerPublicKey = peer,
                address = "10.8.0.2/32",
                dns = listOf("10.8.0.1"),
                mtu = 1280,
                awg = emptyMap(),
            ),
            bypass = com.ardtt.app.profile.BypassConfig(
                peer = "10.0.0.1:443",
                address = "10.8.0.2/32",
                password = "x",
                workers = 2,
                transport = "tcp",
                mode = "raw",
                dial = "auto",
            ),
        )
}
