package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionPolicyTest {
    @Test
    fun networkBlipsDoNotCreateACall() {
        assertFalse(
            shouldCreateNewCall(
                ipChanged = true,
                simChanged = true,
                networkHandleChanged = true,
                timeout = true,
                turnQuota = true,
                staleNonce = true,
                allocationMismatch = true,
                anonymTokenOutdated = true,
                credentialsExpired = true,
                callConfirmedDead = false,
                userRequested = false,
            ),
        )
        assertTrue(
            shouldCreateNewCall(
                ipChanged = false,
                simChanged = false,
                networkHandleChanged = false,
                timeout = false,
                turnQuota = false,
                staleNonce = false,
                allocationMismatch = false,
                anonymTokenOutdated = false,
                credentialsExpired = false,
                callConfirmedDead = true,
                userRequested = false,
            ),
        )
    }

    @Test
    fun expiredCredentialsRefreshInsteadOfCreate() {
        assertEquals(
            CallUpdateDecision.RefreshCredentials,
            decideCallUpdate(
                validity = CallValidity.CredentialsExpired,
                underlayAllowsOps = true,
                userRequestedNew = false,
                autoRecreate = true,
                createInFlight = false,
            ),
        )
        assertEquals(
            CallUpdateDecision.ReuseLive,
            decideCallUpdate(
                validity = CallValidity.Valid,
                underlayAllowsOps = true,
                userRequestedNew = false,
                autoRecreate = false,
                createInFlight = false,
            ),
        )
        assertEquals(
            CallUpdateDecision.WaitForNetwork,
            decideCallUpdate(
                validity = CallValidity.Valid,
                underlayAllowsOps = false,
                userRequestedNew = false,
                autoRecreate = false,
                createInFlight = false,
            ),
        )
    }
}

class RecoverySettingsTest {
    @Test
    fun backoffFollowsSpecifiedSequenceWithoutCap() {
        assertEquals(2_000L, RecoverySettings.retryDelayMs(0))
        assertEquals(5_000L, RecoverySettings.retryDelayMs(1))
        assertEquals(10_000L, RecoverySettings.retryDelayMs(2))
        assertEquals(20_000L, RecoverySettings.retryDelayMs(3))
        assertEquals(30_000L, RecoverySettings.retryDelayMs(4))
        assertEquals(60_000L, RecoverySettings.retryDelayMs(5))
        assertEquals(60_000L, RecoverySettings.retryDelayMs(99))
        assertEquals(7_200L, RecoverySettings.retryDelayMs(1, jitterPermille = 440))
    }

    @Test
    fun localAwgStartWithoutHandshakeIsNotConnected() {
        assertFalse(RecoverySettings.directPathLooksConfirmed(totalRx = 0L, handshakeSec = 0L))
        assertTrue(RecoverySettings.directPathLooksConfirmed(totalRx = 12L, handshakeSec = 0L))
        assertFalse(RecoverySettings.directPathLooksConfirmed(totalRx = 0L, handshakeSec = 3L))
        assertTrue(RecoverySettings.directProtocolReady(handshakeSec = 3L))
        assertTrue(
            PathConfirm.looksConfirmed(
                PathConfirmObservation(
                    capturedSessionEpoch = 1L,
                    capturedTransportEpoch = 1L,
                    capturedNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    eventSessionEpoch = 1L,
                    eventTransportEpoch = 1L,
                    eventNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    usefulRxDelta = 12L,
                    handshakeGrew = true,
                    probeSucceeded = false,
                    source = PathConfirmSource.DirectAwg,
                ),
            ),
        )
        assertFalse(
            PathConfirm.looksConfirmed(
                PathConfirmObservation(
                    capturedSessionEpoch = 1L,
                    capturedTransportEpoch = 1L,
                    capturedNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    eventSessionEpoch = 1L,
                    eventTransportEpoch = 1L,
                    eventNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    usefulRxDelta = 0L,
                    handshakeGrew = true,
                    probeSucceeded = false,
                    source = PathConfirmSource.DirectAwg,
                ),
            ),
        )
        assertFalse(
            PathConfirm.looksConfirmed(
                PathConfirmObservation(
                    capturedSessionEpoch = 1L,
                    capturedTransportEpoch = 1L,
                    capturedNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    eventSessionEpoch = 1L,
                    eventTransportEpoch = 1L,
                    eventNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    probeSucceeded = true,
                ),
            ),
        )
        assertFalse(
            PathConfirm.looksConfirmed(
                PathConfirmObservation(
                    capturedSessionEpoch = 1L,
                    capturedTransportEpoch = 1L,
                    capturedNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    eventSessionEpoch = 2L,
                    eventTransportEpoch = 1L,
                    eventNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    handshakeGrew = true,
                    usefulRxDelta = 40L,
                ),
            ),
        )
        assertFalse(
            PathConfirm.looksConfirmed(
                PathConfirmObservation(
                    capturedSessionEpoch = 1L,
                    capturedTransportEpoch = 1L,
                    capturedNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    eventSessionEpoch = 1L,
                    eventTransportEpoch = 1L,
                    eventNetworkKey = NetworkKey(1L, UnderlayKind.Cellular, null, "c"),
                    tunWriteErrDelta = 3L,
                    tunWriteOkDelta = 0L,
                    handshakeGrew = true,
                ),
            ),
        )
        assertEquals(
            PathConfirmResult.Unsupported("provision-tcp-not-awg"),
            provisionTcpIsNotPathProof(),
        )
    }
}

class ConnectionUiPhaseTest {
    @Test
    fun waitingAndRecoveryCopyMatchesProductTable() {
        val wait = connectionUiModel(
            phase = RecoveryPhase.WaitingForNetwork,
            activePath = null,
            restriction = RestrictionHint.Unknown,
            transport = TransportLifecycle.Stopped,
            retryInMs = null,
        )
        assertEquals("Нет подключения. Включите Wi‑Fi или мобильный интернет", wait.message)
        assertTrue(wait.actions.contains(ConnectionUiAction.OpenNetworkSettings))
        val probing = connectionUiModel(
            phase = RecoveryPhase.Probing,
            activePath = null,
            restriction = RestrictionHint.Unknown,
            transport = TransportLifecycle.Starting,
            retryInMs = null,
        )
        assertEquals("Сеть подключена, ожидаем передачу данных", probing.message)
        val connectingCell = connectionUiModel(
            phase = RecoveryPhase.ConnectingDirect,
            activePath = null,
            restriction = RestrictionHint.Unknown,
            transport = TransportLifecycle.Starting,
            retryInMs = null,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals("Подключаемся напрямую через мобильную сеть", connectingCell.message)
        val connectingAfterBypass = connectionUiModel(
            phase = RecoveryPhase.ConnectingDirect,
            activePath = VpnPath.Bypass,
            restriction = RestrictionHint.Unknown,
            transport = TransportLifecycle.Starting,
            retryInMs = null,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals("Подключаемся напрямую через мобильную сеть", connectingAfterBypass.message)
        assertEquals(ConnectionUiPhase.Connecting, connectingAfterBypass.phase)
        val recovering = connectionUiModel(
            phase = RecoveryPhase.Backoff,
            activePath = VpnPath.Direct,
            restriction = RestrictionHint.Unknown,
            transport = TransportLifecycle.Failed,
            retryInMs = 5_000L,
        )
        assertTrue(recovering.message.contains("через 5 с"))
        assertEquals(ConnState.Recovering, recovering.connState)
        val suspected = connectionUiModel(
            phase = RecoveryPhase.Connected,
            activePath = VpnPath.Direct,
            restriction = RestrictionHint.Suspected,
            transport = TransportLifecycle.Running,
            retryInMs = null,
        )
        assertEquals("Прямое подключение работает. Возможны ограничения мобильной сети", suspected.message)
        assertEquals(ConnectionUiPhase.DirectDespiteRestriction, suspected.phase)
        val whitelistBypass = connectionUiModel(
            phase = RecoveryPhase.ConnectingBypass,
            activePath = null,
            restriction = RestrictionHint.Confirmed,
            transport = TransportLifecycle.Starting,
            retryInMs = null,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals(
            "Похоже на белый список оператора. Подключаемся через обход",
            whitelistBypass.message,
        )
    }
}
