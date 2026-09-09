package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionReducerTest {
    private val cellKey = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell")
    private val wifiKey = NetworkKey(2L, UnderlayKind.Wifi, null, "wifi")

    private fun usableCellular(epoch: Long = 1L) = UnderlaySnapshot(
        key = cellKey,
        kind = UnderlayKind.Cellular,
        availability = UnderlayAvailability.Usable,
        handle = 1L,
        simId = 11,
        cellularConnected = true,
        networkEpoch = epoch,
    )

    private fun usableWifi(epoch: Long = 2L) = UnderlaySnapshot(
        key = wifiKey,
        kind = UnderlayKind.Wifi,
        availability = UnderlayAvailability.Usable,
        handle = 2L,
        wifiConnected = true,
        cellularConnected = true,
        networkEpoch = epoch,
    )

    private fun idle() = ConnectionSnapshot()

    @Test
    fun connectWithoutNetworkWaitsAndIssuesNoTransport() {
        val r = ConnectionReducer.reduce(
            idle(),
            ConnectionEvent.UserConnect(
                mode = ConnPathMode.Auto,
                profileId = "p",
                hasCallHash = true,
                silentRecreate = false,
            ),
            elapsedMs = 10L,
        )
        assertTrue(r.state.intent.wantsConnected)
        assertEquals(RecoveryPhase.WaitingForNetwork, r.state.recovery.phase)
        assertEquals(RecoveryCommand.PauseNetOps, r.command)
        assertEquals(ConnState.WaitingForNetwork, r.state.ui.connState)
        assertTrue(r.state.ui.actions.contains(ConnectionUiAction.OpenNetworkSettings))
        assertTrue(r.state.ui.actions.contains(ConnectionUiAction.CancelWait))
        assertFalse(r.state.underlay.allowsNetworkOps)
    }

    @Test
    fun networkReturnStartsOneRecoveryCycle() {
        val waiting = ConnectionReducer.reduce(
            idle(),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", hasCallHash = true, silentRecreate = false),
            10L,
        ).state
        val back = ConnectionReducer.reduce(
            waiting,
            ConnectionEvent.UnderlayUpdated(usableCellular()),
            elapsedMs = 20L,
        )
        assertTrue(back.command is RecoveryCommand.StartDirect)
        assertEquals(RecoveryPhase.ConnectingDirect, back.state.recovery.phase)
        val again = ConnectionReducer.reduce(
            back.state.copy(recovery = back.state.recovery.copy(inFlight = true)),
            ConnectionEvent.UnderlayUpdated(usableCellular()),
            elapsedMs = 25L,
        )
        // Same usable network while in-flight: Auto stays on Direct try, no second call create.
        assertTrue(again.command is RecoveryCommand.None || again.command is RecoveryCommand.StartDirect)
        assertNull(again.diagnostic?.takeIf { it == SessionDiagnostic.CallCreated })
    }

    @Test
    fun airplaneWifiStillGoesDirect() {
        val connected = ConnectionReducer.reduce(
            idle().copy(underlay = usableWifi()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", hasCallHash = true, silentRecreate = false),
            5L,
        )
        val start = connected.command as RecoveryCommand.StartDirect
        assertTrue(start.keepCall)
        assertEquals(VpnPath.Direct, connected.state.activePath)
    }

    @Test
    fun disconnectCancelsAndIgnoresLateEvents() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            1L,
        )
        val stopped = ConnectionReducer.reduce(started.state, ConnectionEvent.UserDisconnect, 2L)
        assertEquals(RecoveryCommand.StopAll, stopped.command)
        assertFalse(stopped.state.intent.wantsConnected)
        val late = ConnectionReducer.reduce(
            stopped.state,
            ConnectionEvent.DirectConfirmed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = cellKey,
            ),
            3L,
        )
        assertEquals(RecoveryCommand.None, late.command)
        assertFalse(late.state.intent.wantsConnected)
    }

    @Test
    fun temporaryFailuresBackoffWithoutGivingUp() {
        var state = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Direct, "p", false, false),
            0L,
        ).state
        val transportEpoch = state.transportEpoch
        repeat(12) { i ->
            val failed = ConnectionReducer.reduce(
                state,
                ConnectionEvent.DirectFailed(
                    sessionEpoch = state.sessionEpoch,
                    transportEpoch = state.transportEpoch,
                    networkKey = cellKey,
                    reason = "temp",
                ),
                elapsedMs = i * 1_000L,
            )
            assertTrue(failed.state.intent.wantsConnected)
            assertEquals(RecoveryPhase.Backoff, failed.state.recovery.phase)
            assertEquals(ConnState.Recovering, failed.state.ui.connState)
            state = ConnectionReducer.reduce(
                failed.state,
                ConnectionEvent.Clock(elapsedMs = failed.state.recovery.nextRetryAtElapsedMs ?: 0L),
                elapsedMs = failed.state.recovery.nextRetryAtElapsedMs ?: 0L,
            ).state
        }
        assertTrue(state.intent.wantsConnected)
        assertTrue(state.recovery.failureIndex >= 6)
        assertTrue(state.transportEpoch >= transportEpoch)
    }

    @Test
    fun staleProbeFromOldNetworkIsIgnored() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular(epoch = 1L), networkEpoch = 1L),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            1L,
        )
        val moved = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.UnderlayUpdated(usableWifi(epoch = 2L)),
            2L,
        )
        val stale = ConnectionReducer.reduce(
            moved.state,
            ConnectionEvent.ProbeFinished(
                evidence = ReachabilityEvidence(restriction = RestrictionHint.Suspected),
                sessionEpoch = started.state.sessionEpoch,
                networkEpoch = 1L,
            ),
            3L,
        )
        assertEquals(RecoveryCommand.None, stale.command)
    }

    @Test
    fun wifiSwitchParksCallAndResumeReusesIt() {
        var state = ConnectionReducer.reduce(
            idle().copy(
                underlay = usableCellular(),
                parkedRawAlive = true,
                activePath = VpnPath.Bypass,
                transport = TransportLifecycle.Running,
                call = CallSessionState(hashPresent = true),
            ),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            1L,
        ).state
        state = state.copy(
            parkedRawAlive = true,
            activePath = VpnPath.Bypass,
            transport = TransportLifecycle.Running,
            call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
        )
        val toWifi = ConnectionReducer.reduce(
            state,
            ConnectionEvent.UnderlayUpdated(usableWifi()),
            2L,
        )
        assertEquals(RecoveryCommand.ParkBypassForDirect, toWifi.command)
        assertEquals(RecoveryPhase.SwitchingToWifi, toWifi.state.recovery.phase)
        val confirmed = ConnectionReducer.reduce(
            toWifi.state,
            ConnectionEvent.DirectConfirmed(
                sessionEpoch = toWifi.state.sessionEpoch,
                transportEpoch = toWifi.state.transportEpoch,
                networkKey = wifiKey,
            ),
            3L,
        )
        assertEquals(VpnPath.Direct, confirmed.state.activePath)
        assertTrue(confirmed.state.parkedRawAlive)
        val back = ConnectionReducer.reduce(
            confirmed.state.copy(
                directFailedOnNetwork = wifiKey,
                wifiFailStreak = 1,
                transport = TransportLifecycle.Failed,
            ),
            ConnectionEvent.UnderlayUpdated(usableCellular(epoch = 3L)),
            4L,
        )
        assertTrue(
            back.command == RecoveryCommand.ResumeParkedRaw ||
                back.command is RecoveryCommand.StartBypass,
        )
        if (back.command == RecoveryCommand.ResumeParkedRaw) {
            assertEquals(SessionDiagnostic.TransportResumed, back.diagnostic)
        }
    }

    @Test
    fun credentialsExpiredRefreshSameCall() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular(), call = CallSessionState(hashPresent = true)),
            ConnectionEvent.UserConnect(ConnPathMode.Bypass, "p", true, false),
            1L,
        )
        val expired = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.CallValidityChanged(
                sessionEpoch = started.state.sessionEpoch,
                validity = CallValidity.CredentialsExpired,
            ),
            2L,
        )
        assertEquals(RecoveryCommand.RefreshCredentials, expired.command)
        assertEquals(SessionDiagnostic.CredentialsRefreshed, expired.diagnostic)
        assertEquals(CallValidity.CredentialsExpired, expired.state.call.validity)
    }

    @Test
    fun backoffCopyUsesRemainingNotAbsoluteElapsed() {
        val state = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Direct, "p", false, false),
            elapsedMs = 1_000_000L,
        ).state
        val failed = ConnectionReducer.reduce(
            state,
            ConnectionEvent.DirectFailed(
                sessionEpoch = state.sessionEpoch,
                transportEpoch = state.transportEpoch,
                networkKey = cellKey,
                reason = "temp",
            ),
            elapsedMs = 1_000_000L,
        )
        assertEquals(RecoveryPhase.Backoff, failed.state.recovery.phase)
        assertEquals(2_000L, failed.state.ui.retryInMs)
        assertTrue(failed.state.ui.message.contains("через 2 с"))
        assertTrue((failed.state.ui.retryInMs ?: 0L) < 10_000L)
        val due = failed.state.recovery.nextRetryAtElapsedMs ?: 0L
        val retry = ConnectionReducer.reduce(
            failed.state,
            ConnectionEvent.Clock(elapsedMs = due),
            elapsedMs = due,
        )
        assertTrue(retry.command is RecoveryCommand.StartDirect)
        assertEquals(RecoveryPhase.ConnectingDirect, retry.state.recovery.phase)
    }

    @Test
    fun inFlightSamePathDoesNotDoubleStart() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            10L,
        )
        assertTrue(started.command is RecoveryCommand.StartDirect)
        val again = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.UnderlayUpdated(usableCellular()),
            15L,
        )
        assertEquals(RecoveryCommand.None, again.command)
    }

    @Test
    fun returningToMobileUsesResumeAndUiPhase() {
        val started = ConnectionReducer.reduce(
            idle().copy(
                underlay = usableWifi(),
                parkedRawAlive = true,
                activePath = VpnPath.Direct,
                transport = TransportLifecycle.Failed,
                wifiFailStreak = 2,
                call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
            ),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            1L,
        )
        val back = ConnectionReducer.reduce(
            started.state.copy(
                parkedRawAlive = true,
                activePath = VpnPath.Direct,
                transport = TransportLifecycle.Failed,
                wifiFailStreak = 2,
                call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
                recovery = started.state.recovery.copy(inFlight = false),
            ),
            ConnectionEvent.UnderlayUpdated(usableWifi().copy(cellularConnected = true)),
            2L,
        )
        assertEquals(RecoveryCommand.ResumeParkedRaw, back.command)
        assertEquals(RecoveryPhase.ReturningToMobile, back.state.recovery.phase)
        assertEquals(SessionDiagnostic.TransportResumed, back.diagnostic)
    @Test
    fun parkedProcessDeathClearsResumeWithoutNewCall() {
        val connected = ConnectionReducer.reduce(
            idle().copy(underlay = usableWifi(), parkedRawAlive = true),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            1L,
        )
        val ok = ConnectionReducer.reduce(
            connected.state.copy(parkedRawAlive = true, activePath = VpnPath.Direct),
            ConnectionEvent.DirectConfirmed(
                sessionEpoch = connected.state.sessionEpoch,
                transportEpoch = connected.state.transportEpoch,
                networkKey = wifiKey,
            ),
            2L,
        )
        val died = ConnectionReducer.reduce(
            ok.state,
            ConnectionEvent.ParkedProcessDied(sessionEpoch = ok.state.sessionEpoch),
            3L,
        )
        assertFalse(died.state.parkedRawAlive)
        assertEquals(RecoveryCommand.None, died.command)
        assertTrue(died.state.intent.wantsConnected)
        assertNull(died.diagnostic)
    }
}
