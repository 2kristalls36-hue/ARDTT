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
        assertEquals("Подключено напрямую", suspected.message)
        assertEquals(ConnectionUiPhase.DirectDespiteRestriction, suspected.phase)
    }
}
