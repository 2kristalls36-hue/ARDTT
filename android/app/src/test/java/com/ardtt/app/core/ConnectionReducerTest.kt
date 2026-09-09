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
                probeConfirmed = true,
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
                probeConfirmed = true,
            ),
            3L,
        )
        assertEquals(VpnPath.Direct, confirmed.state.activePath)
        assertTrue(confirmed.state.parkedRawAlive)
        val back = ConnectionReducer.reduce(
            confirmed.state.copy(
                directNegative = DirectNegativeEvidence(
                    key = wifiKey,
                    retryAfterElapsedMs = Long.MAX_VALUE,
                ),
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
        assertEquals("Восстанавливаем обход через существующий звонок", back.state.ui.message)
    }

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
                probeConfirmed = true,
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

    @Test
    fun coldConnectOnUsableNetworkStartsOnceWithPermit() {
        val r = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            elapsedMs = 10L,
        )
        assertTrue(r.command is RecoveryCommand.StartDirect)
        assertFalse(r.state.recovery.permit.userStop)
        assertTrue(r.state.recovery.permit.netOpsAllowed)
        assertEquals(r.state.sessionEpoch, r.state.recovery.permit.sessionEpoch)
        assertEquals(r.state.transportEpoch, r.state.recovery.permit.transportEpoch)
        val again = ConnectionReducer.reduce(
            r.state,
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            elapsedMs = 11L,
        )
        assertTrue(again.state.sessionEpoch > r.state.sessionEpoch)
        assertTrue(again.command is RecoveryCommand.StartDirect || again.command is RecoveryCommand.None)
    }

    @Test
    fun disconnectThenConnectStartsNewSessionAndIgnoresStale() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", false, false),
            1L,
        )
        val stopped = ConnectionReducer.reduce(started.state, ConnectionEvent.UserDisconnect, 2L)
        val again = ConnectionReducer.reduce(
            stopped.state.copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", false, false),
            3L,
        )
        assertTrue(again.state.sessionEpoch > started.state.sessionEpoch)
        assertTrue(again.command is RecoveryCommand.StartDirect)
        assertFalse(again.state.recovery.permit.userStop)
        val stale = ConnectionReducer.reduce(
            again.state,
            ConnectionEvent.DirectConfirmed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = cellKey,
                probeConfirmed = true,
            ),
            4L,
        )
        assertEquals(RecoveryCommand.None, stale.command)
        assertEquals(TransportLifecycle.Starting, stale.state.transport)
    }

    @Test
    fun autoWithoutHashRetriesDirectAfterEvidenceExpires() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", false, false),
            0L,
        )
        assertTrue(started.command is RecoveryCommand.StartDirect)
        val failed = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.DirectFailed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = cellKey,
                reason = "temp",
            ),
            elapsedMs = 0L,
        )
        assertEquals(RecoveryPhase.Backoff, failed.state.recovery.phase)
        assertTrue(failed.command is RecoveryCommand.ScheduleRetry)
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
    fun retryNowDoesNotDoubleStartWithDueClock() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Direct, "p", false, false),
            0L,
        )
        val failed = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.DirectFailed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = cellKey,
                reason = "temp",
            ),
            elapsedMs = 0L,
        )
        val retried = ConnectionReducer.reduce(
            failed.state,
            ConnectionEvent.UserRetryNow,
            elapsedMs = 1L,
        )
        assertTrue(retried.command is RecoveryCommand.StartDirect)
        val clock = ConnectionReducer.reduce(
            retried.state,
            ConnectionEvent.Clock(elapsedMs = failed.state.recovery.nextRetryAtElapsedMs ?: 2_000L),
            elapsedMs = failed.state.recovery.nextRetryAtElapsedMs ?: 2_000L,
        )
        assertEquals(RecoveryCommand.None, clock.command)
    }

    @Test
    fun profileChangeDuringRecoveryUsesNewIntent() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "old", true, false),
            1L,
        )
        val changed = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.SessionParamsChanged(profileId = "new", hasCallHash = false),
            2L,
        )
        assertEquals("new", changed.state.intent.profileId)
        assertFalse(changed.state.intent.hasCallHash)
        assertNull(changed.state.directNegative)
        assertTrue(changed.command is RecoveryCommand.StartDirect || changed.command is RecoveryCommand.None)
    }

    @Test
    fun sameNetworkDuringBackoffDoesNotDoubleSchedule() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Direct, "p", false, false),
            0L,
        )
        val failed = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.DirectFailed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = cellKey,
                reason = "temp",
            ),
            elapsedMs = 0L,
        )
        assertTrue(failed.command is RecoveryCommand.ScheduleRetry)
        val idx = failed.state.recovery.failureIndex
        val again = ConnectionReducer.reduce(
            failed.state,
            ConnectionEvent.UnderlayUpdated(usableCellular()),
            elapsedMs = 100L,
        )
        assertEquals(RecoveryCommand.None, again.command)
        assertEquals(idx, again.state.recovery.failureIndex)
        assertEquals(RecoveryPhase.Backoff, again.state.recovery.phase)
    }

    @Test
    fun networkChangeAfterDirectFailStartsImmediately() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            0L,
        )
        val failed = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.DirectFailed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = cellKey,
                reason = "temp",
            ),
            elapsedMs = 0L,
        )
        val wifi = ConnectionReducer.reduce(
            failed.state,
            ConnectionEvent.UnderlayUpdated(usableWifi()),
            elapsedMs = 50L,
        )
        assertTrue(
            wifi.command is RecoveryCommand.StartDirect ||
                wifi.command == RecoveryCommand.ParkBypassForDirect,
        )
        assertEquals(TransportLifecycle.Starting, wifi.state.transport)
    }

    @Test
    fun parkedDeathThenReturnToMobileRebuildsSameCall() {
        val started = ConnectionReducer.reduce(
            idle().copy(
                underlay = usableWifi(),
                parkedRawAlive = false,
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
                parkedRawAlive = false,
                activePath = VpnPath.Direct,
                transport = TransportLifecycle.Failed,
                wifiFailStreak = 2,
                call = CallSessionState(hashPresent = true, validity = CallValidity.Valid),
                recovery = started.state.recovery.copy(inFlight = false, nextRetryAtElapsedMs = null),
            ),
            ConnectionEvent.UnderlayUpdated(usableWifi().copy(cellularConnected = true)),
            2L,
        )
        assertEquals(RecoveryCommand.RebuildRawSameCall, back.command)
        assertEquals(SessionDiagnostic.TransportRebuilt, back.diagnostic)
        assertEquals(RecoveryPhase.ReturningToMobile, back.state.recovery.phase)
    }

    @Test
    fun parkedRawSurvivesFifteenMinutesWithoutFixedKill() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableWifi(), parkedRawAlive = true),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            1L,
        )
        val ok = ConnectionReducer.reduce(
            started.state.copy(parkedRawAlive = true, activePath = VpnPath.Direct),
            ConnectionEvent.DirectConfirmed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = wifiKey,
                probeConfirmed = true,
            ),
            2L,
        )
        assertTrue(ok.state.parkedRawAlive)
        val sixMin = ConnectionReducer.reduce(
            ok.state,
            ConnectionEvent.Clock(elapsedMs = 6L * 60_000L),
            elapsedMs = 6L * 60_000L,
        )
        assertTrue(sixMin.state.parkedRawAlive)
        assertTrue(sixMin.state.intent.wantsConnected)
        assertEquals(RecoveryCommand.None, sixMin.command)
        val fifteen = ConnectionReducer.reduce(
            sixMin.state,
            ConnectionEvent.Clock(elapsedMs = 15L * 60_000L),
            elapsedMs = 15L * 60_000L,
        )
        assertTrue(fifteen.state.parkedRawAlive)
        assertEquals(RecoveryCommand.None, fifteen.command)
        assertEquals(RecoveryPhase.Connected, fifteen.state.recovery.phase)
    }

    @Test
    fun captchaNeedsUserActionThenAutoCanRetryDirect() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            1L,
        )
        val bypassing = started.state.copy(
            activePath = VpnPath.Bypass,
            transport = TransportLifecycle.Starting,
            recovery = started.state.recovery.copy(inFlight = true),
        )
        val captcha = ConnectionReducer.reduce(
            bypassing,
            ConnectionEvent.BypassFailed(
                sessionEpoch = bypassing.sessionEpoch,
                transportEpoch = bypassing.transportEpoch,
                reason = "captcha required",
                userAction = UserActionKind.Captcha,
            ),
            2L,
        )
        assertEquals(RecoveryPhase.NeedsUserAction, captcha.state.recovery.phase)
        assertEquals(RecoveryCommand.None, captcha.command)
        assertTrue(captcha.state.intent.wantsConnected)
        val retry = ConnectionReducer.reduce(
            captcha.state,
            ConnectionEvent.UserRetryNow,
            elapsedMs = 3L,
        )
        assertTrue(retry.command is RecoveryCommand.StartDirect)
        assertEquals(RecoveryPhase.ConnectingDirect, retry.state.recovery.phase)
    }

    @Test
    fun pathModeChangeDuringRecoveryUsesNewMode() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            1L,
        )
        val forced = ConnectionReducer.reduce(
            started.state.copy(recovery = started.state.recovery.copy(inFlight = false)),
            ConnectionEvent.PathModeChanged(ConnPathMode.Bypass),
            2L,
        )
        assertEquals(ConnPathMode.Bypass, forced.state.intent.mode)
        assertTrue(
            forced.command is RecoveryCommand.StartBypass ||
                forced.command == RecoveryCommand.ResumeParkedRaw ||
                forced.command == RecoveryCommand.RefreshCredentials,
        )
    }

    @Test
    fun handshakeWithoutProbeDoesNotConfirmDirect() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Direct, "p", false, false),
            1L,
        )
        val late = ConnectionReducer.reduce(
            started.state.copy(wifiFailStreak = 2),
            ConnectionEvent.DirectConfirmed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = cellKey,
                probeConfirmed = false,
            ),
            2L,
        )
        assertEquals(2, late.state.wifiFailStreak)
        assertTrue(late.state.recovery.phase != RecoveryPhase.Connected)
    }

    @Test
    fun directConfirmFromStaleNetworkIsIgnored() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableWifi()),
            ConnectionEvent.UserConnect(ConnPathMode.Direct, "p", false, false),
            1L,
        )
        val late = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.DirectConfirmed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = cellKey,
                probeConfirmed = true,
            ),
            2L,
        )
        assertTrue(late.state.recovery.phase != RecoveryPhase.Connected)
        assertEquals(0, late.state.wifiFailStreak)
    }

    @Test
    fun newCallIdentityClearsDeadValidityAndParksOldProcess() {
        val started = ConnectionReducer.reduce(
            idle().copy(
                underlay = usableCellular(),
                call = CallSessionState(
                    hashPresent = true,
                    validity = CallValidity.ConfirmedDead,
                    identityToken = "old",
                ),
                parkedRawAlive = true,
            ),
            ConnectionEvent.UserConnect(
                ConnPathMode.Bypass,
                "p",
                true,
                false,
                callIdentityToken = "old",
            ),
            1L,
        )
        assertEquals(CallValidity.ConfirmedDead, started.state.call.validity)
        val swapped = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.CallIdentityChanged(
                profileId = "p",
                hashPresent = true,
                identityToken = "new",
            ),
            2L,
        )
        assertEquals(CallValidity.Valid, swapped.state.call.validity)
        assertEquals("new", swapped.state.call.identityToken)
        assertFalse(swapped.state.parkedRawAlive)
        assertTrue(swapped.state.call.callEpoch > started.state.call.callEpoch)
        assertTrue(
            swapped.command is RecoveryCommand.StartBypass ||
                swapped.command == RecoveryCommand.RebuildRawSameCall,
        )
    }

    @Test
    fun bypassConfirmedSchedulesDirectReevalAfterEvidenceExpires() {
        val started = ConnectionReducer.reduce(
            idle().copy(underlay = usableCellular()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", true, false),
            0L,
        )
        val failed = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.DirectFailed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                networkKey = cellKey,
                reason = "temp",
            ),
            elapsedMs = 0L,
        )
        val connecting = failed.command is RecoveryCommand.StartBypass ||
            failed.command == RecoveryCommand.ResumeParkedRaw ||
            failed.command == RecoveryCommand.RebuildRawSameCall
        assertTrue(connecting || failed.state.directNegative != null)
        val bypassing = if (failed.state.transport == TransportLifecycle.Starting &&
            failed.state.activePath == VpnPath.Bypass
        ) {
            failed
        } else {
            ConnectionReducer.reduce(
                failed.state.copy(
                    activePath = VpnPath.Bypass,
                    transport = TransportLifecycle.Starting,
                    parkedRawAlive = true,
                    recovery = failed.state.recovery.copy(inFlight = true),
                ),
                ConnectionEvent.Clock(elapsedMs = failed.state.recovery.nextRetryAtElapsedMs ?: 2_000L),
                elapsedMs = failed.state.recovery.nextRetryAtElapsedMs ?: 2_000L,
            )
        }
        val ok = ConnectionReducer.reduce(
            bypassing.state.copy(
                activePath = VpnPath.Bypass,
                transport = TransportLifecycle.Starting,
                parkedRawAlive = true,
            ),
            ConnectionEvent.BypassConfirmed(
                sessionEpoch = bypassing.state.sessionEpoch,
                transportEpoch = bypassing.state.transportEpoch,
                probeConfirmed = true,
            ),
            elapsedMs = 3_000L,
        )
        assertEquals(RecoveryPhase.Connected, ok.state.recovery.phase)
        assertEquals(VpnPath.Bypass, ok.state.activePath)
        val due = ok.state.recovery.nextRetryAtElapsedMs
        assertTrue(due != null)
        val reeval = ConnectionReducer.reduce(
            ok.state.copy(recovery = ok.state.recovery.copy(inFlight = false)),
            ConnectionEvent.Clock(elapsedMs = (due ?: 0L) + 1L),
            elapsedMs = (due ?: 0L) + 1L,
        )
        assertTrue(
            reeval.command == RecoveryCommand.ParkBypassForDirect ||
                reeval.command is RecoveryCommand.StartDirect,
        )
    }
}
