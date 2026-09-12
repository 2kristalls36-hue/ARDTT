package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathConfirmAssemblerTest {
    private val key = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell")

    @Test
    fun workersAloneAreBackendRunningNotPathConfirmed() {
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 4L,
            eventCallEpoch = 4L,
            capturedTunGen = 2L,
            eventTunGen = 2L,
            tunWriteOkDelta = 0L,
            tunWriteErrDelta = 0L,
            usefulRxDelta = 0L,
            workersPresent = true,
        )
        assertEquals(PathConfirmVerdict.BackendRunning, PathConfirm.verdict(obs))
        assertFalse(PathConfirm.looksConfirmed(obs))
        assertTrue(PathConfirm.bypassMayConnect(PathConfirm.verdict(obs)))
    }

    @Test
    fun idleWorkersAreNotAFailureAndNotPathConfirmed() {
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 4L,
            eventCallEpoch = 4L,
            capturedTunGen = 2L,
            eventTunGen = 2L,
            tunWriteOkDelta = 0L,
            tunWriteErrDelta = 0L,
            usefulRxDelta = 0L,
            workersPresent = true,
        )
        assertEquals(PathConfirmVerdict.BackendRunning, PathConfirm.verdict(obs))
        assertFalse(PathConfirm.looksConfirmed(obs))
    }

    @Test
    fun exactDownBelowLogRoundingConfirmsBypass() {
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 4L,
            eventCallEpoch = 4L,
            capturedTunGen = 2L,
            eventTunGen = 2L,
            tunWriteOkDelta = 0L,
            tunWriteErrDelta = 0L,
            usefulRxDelta = 1200L,
            workersPresent = true,
        )
        assertEquals(PathConfirmVerdict.PathConfirmed, PathConfirm.verdict(obs))
        assertTrue(1200L < 0.01 * 1024 * 1024)
    }

    @Test
    fun tunWriteOkDeltaConfirmsBypass() {
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 4L,
            eventCallEpoch = 4L,
            capturedTunGen = 7L,
            eventTunGen = 7L,
            tunWriteOkDelta = 3L,
            tunWriteErrDelta = 0L,
            usefulRxDelta = 0L,
            workersPresent = true,
        )
        assertEquals(PathConfirmVerdict.PathConfirmed, PathConfirm.verdict(obs))
    }

    @Test
    fun tunWriteErrWithoutOkIsWriteFailed() {
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 4L,
            eventCallEpoch = 4L,
            capturedTunGen = 7L,
            eventTunGen = 7L,
            tunWriteOkDelta = 0L,
            tunWriteErrDelta = 2L,
            usefulRxDelta = 0L,
            workersPresent = true,
        )
        assertEquals(PathConfirmVerdict.WriteFailed, PathConfirm.verdict(obs))
        assertFalse(PathConfirm.bypassMayConnect(PathConfirm.verdict(obs)))
    }

    @Test
    fun staleTunGenerationIsNotConfirmed() {
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 4L,
            eventCallEpoch = 4L,
            capturedTunGen = 7L,
            eventTunGen = 9L,
            tunWriteOkDelta = 4L,
            tunWriteErrDelta = 0L,
            usefulRxDelta = 80L,
            workersPresent = true,
        )
        assertEquals(PathConfirmVerdict.Stale, PathConfirm.verdict(obs))
        assertFalse(PathConfirm.looksConfirmed(obs))
    }

    @Test
    fun staleCallEpochIsNotConfirmed() {
        val obs = PathConfirm.assembleBypass(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 4L,
            eventCallEpoch = 5L,
            capturedTunGen = 2L,
            eventTunGen = 2L,
            tunWriteOkDelta = 4L,
            tunWriteErrDelta = 0L,
            usefulRxDelta = 80L,
            workersPresent = true,
        )
        assertEquals(PathConfirmVerdict.Stale, PathConfirm.verdict(obs))
    }

    @Test
    fun uidAndSysfsRxDoNotConfirmDirect() {
        val uid = PathConfirm.assembleDirect(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedHandle = 3L,
            eventHandle = 3L,
            handshakeBaselineSec = 0L,
            handshakeNowSec = 0L,
            rxBaseline = 0L,
            rxNow = 40_000L,
            source = PathConfirmSource.UidFallback,
        )
        assertEquals(PathConfirmVerdict.NotReady, PathConfirm.verdict(uid))
        assertEquals(0L, uid.usefulRxDelta)
        val sysfsAssembled = PathConfirm.assembleDirect(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedHandle = 3L,
            eventHandle = 3L,
            handshakeBaselineSec = 0L,
            handshakeNowSec = 0L,
            rxBaseline = 0L,
            rxNow = 40_000L,
            source = PathConfirmSource.Sysfs,
        )
        assertEquals(PathConfirmVerdict.NotReady, PathConfirm.verdict(sysfsAssembled))
        assertEquals(0L, sysfsAssembled.usefulRxDelta)
    }

    @Test
    fun oldHandshakeWithoutGrowthIsNotProtocolReadyOnReuse() {
        val obs = PathConfirm.assembleDirect(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedHandle = 8L,
            eventHandle = 8L,
            handshakeBaselineSec = 12L,
            handshakeNowSec = 12L,
            rxBaseline = 0L,
            rxNow = 0L,
            source = PathConfirmSource.DirectAwg,
        )
        assertEquals(PathConfirmVerdict.NotReady, PathConfirm.verdict(obs))
        assertFalse(PathConfirm.protocolReady(obs))
    }

    @Test
    fun handshakeGrowthIsProtocolReadyNotPathConfirmed() {
        val obs = PathConfirm.assembleDirect(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedHandle = 8L,
            eventHandle = 8L,
            handshakeBaselineSec = 0L,
            handshakeNowSec = 3L,
            rxBaseline = 0L,
            rxNow = 0L,
            source = PathConfirmSource.DirectAwg,
        )
        assertEquals(PathConfirmVerdict.ProtocolReady, PathConfirm.verdict(obs))
        assertFalse(PathConfirm.looksConfirmed(obs))
        assertTrue(PathConfirm.directMayConnect(PathConfirm.verdict(obs)))
    }

    /** rx must exceed the handshake/keepalive size — 512 B is protocol chatter. */
    @Test
    fun handshakeSizedAwgRxIsOnlyProtocolReady() {
        val obs = PathConfirm.assembleDirect(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedHandle = 8L,
            eventHandle = 8L,
            handshakeBaselineSec = 0L,
            handshakeNowSec = 3L,
            rxBaseline = 0L,
            rxNow = 512L,
            source = PathConfirmSource.DirectAwg,
        )
        assertEquals(0L, obs.usefulRxDelta)
        assertEquals(PathConfirmVerdict.ProtocolReady, PathConfirm.verdict(obs))
        assertTrue(PathConfirm.directMayConnect(PathConfirm.verdict(obs)))
    }

    @Test
    fun awgRxConfirmsDirect() {
        val obs = PathConfirm.assembleDirect(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 1L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 1L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedHandle = 8L,
            eventHandle = 8L,
            handshakeBaselineSec = 0L,
            handshakeNowSec = 3L,
            rxBaseline = 0L,
            rxNow = 8_192L,
            source = PathConfirmSource.DirectAwg,
        )
        assertEquals(PathConfirmVerdict.PathConfirmed, PathConfirm.verdict(obs))
    }

    @Test
    fun newBackendHandshakeCountsAsProtocolReady() {
        val obs = PathConfirm.assembleDirect(
            capturedSessionEpoch = 1L,
            capturedTransportEpoch = 2L,
            capturedNetworkKey = key,
            eventSessionEpoch = 1L,
            eventTransportEpoch = 2L,
            eventNetworkKey = key,
            capturedCallEpoch = 1L,
            eventCallEpoch = 1L,
            capturedHandle = 1L,
            eventHandle = 2L,
            handshakeBaselineSec = 40L,
            handshakeNowSec = 1L,
            rxBaseline = 0L,
            rxNow = 0L,
            source = PathConfirmSource.DirectAwg,
        )
        assertEquals(PathConfirmVerdict.ProtocolReady, PathConfirm.verdict(obs))
    }
}
