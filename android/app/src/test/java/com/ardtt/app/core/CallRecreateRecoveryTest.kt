package com.ardtt.app.core

import com.ardtt.app.bypass.AUTH_REQUIRED_MESSAGE
import com.ardtt.app.bypass.CallHashErrorKind
import com.ardtt.app.bypass.CallHashFailure
import com.ardtt.app.bypass.CallHashOutcome
import com.ardtt.app.bypass.CallHashPhase
import com.ardtt.app.bypass.CallRecreateIdentity
import com.ardtt.app.bypass.INVALID_RESPONSE_MESSAGE
import com.ardtt.app.bypass.TRANSIENT_NETWORK_MESSAGE
import com.ardtt.app.bypass.beginCallRecreateChanged
import com.ardtt.app.bypass.callRecreateChangedFromOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6-D1/D2: generator → recreate event → reducer → UI/commands.
 * Does not stop at evaluate/plan helpers.
 */
class CallRecreateRecoveryTest {
    private val cellKey = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell", carrier = "25001")
    private val wifiKey = NetworkKey(2L, UnderlayKind.Wifi, null, "wifi")
    private val hash = "LrSXnSsyDo_yNx28kZQp9GBC-8T7xjCAx4D0tJ2paLI"

    private fun usableCellular(epoch: Long = 1L) = UnderlaySnapshot(
        key = cellKey,
        kind = UnderlayKind.Cellular,
        availability = UnderlayAvailability.Usable,
        handle = 1L,
        simId = 11,
        cellularConnected = true,
        networkEpoch = epoch,
    )

    private fun noNetwork(epoch: Long = 9L) = UnderlaySnapshot(
        key = null,
        kind = UnderlayKind.Other,
        availability = UnderlayAvailability.None,
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

    private fun whitelistEvidence() = ReachabilityEvidence(
        networkKey = cellKey,
        profileId = "p",
        yandex = CheckOutcome.Success,
        bigtech = CheckOutcome.Timeout,
        google = CheckOutcome.Timeout,
        ruService = CheckOutcome.Success,
        restriction = RestrictionHint.Confirmed,
        whitelistScorePercent = 80,
    ).withFreshStrongTtl(atElapsedMs = 1L)

    private fun identityOf(state: ConnectionSnapshot, generation: Long = 4L) = CallRecreateIdentity(
        sessionEpoch = state.sessionEpoch,
        generation = if (state.call.createGeneration != 0L) state.call.createGeneration else generation,
        profileId = state.intent.profileId,
        callEpoch = state.call.callEpoch,
        requestId = 8L,
        wantsConnected = state.intent.wantsConnected,
    )

    private fun reduce(
        state: ConnectionSnapshot,
        event: ConnectionEvent,
        elapsedMs: Long,
    ): ReduceResult = ConnectionReducer.reduce(state, event, elapsedMs, jitterPermille = 0)

    private fun connectBypass(
        mode: ConnPathMode = ConnPathMode.Bypass,
        confirm: Boolean = true,
    ): ConnectionSnapshot {
        val started = reduce(
            ConnectionSnapshot(
                underlay = usableCellular(),
                call = CallSessionState(
                    hashPresent = true,
                    validity = CallValidity.Valid,
                    profileId = "p",
                    identityToken = "h",
                    callEpoch = 2L,
                ),
                evidence = whitelistEvidence(),
            ),
            ConnectionEvent.UserConnect(
                mode = mode,
                profileId = "p",
                hasCallHash = true,
                silentRecreate = true,
                callIdentityToken = "h",
            ),
            1L,
        )
        if (!confirm) return started.state
        return reduce(
            started.state,
            ConnectionEvent.BypassConfirmed(
                sessionEpoch = started.state.sessionEpoch,
                transportEpoch = started.state.transportEpoch,
                pathConfirmed = true,
                callEpoch = started.state.call.callEpoch,
            ),
            10L,
        ).state
    }

    private fun begin(state: ConnectionSnapshot, elapsedMs: Long, hold: Boolean = true): ReduceResult {
        return reduce(
            state,
            beginCallRecreateChanged(
                identity = identityOf(state),
                holdService = hold,
                underlayAllowsOps = state.underlay.allowsNetworkOps,
                networkAttempts = state.call.createNetworkAttempts,
            ),
            elapsedMs,
        )
    }

    private fun applyOutcome(
        state: ConnectionSnapshot,
        outcome: CallHashOutcome,
        elapsedMs: Long,
        underlayAllowsOps: Boolean = state.underlay.allowsNetworkOps,
        live: CallRecreateIdentity = identityOf(state),
    ): ReduceResult {
        val event = callRecreateChangedFromOutcome(
            captured = identityOf(state),
            live = live,
            outcome = outcome,
            holdService = state.call.createHold,
            networkAttempts = state.call.createNetworkAttempts,
            underlayAllowsOps = underlayAllowsOps,
        )
        assertTrue("stale/cancel must be explicit in those tests", event != null)
        return reduce(state, event!!, elapsedMs)
    }

    private fun ioFailure() = CallHashOutcome.Failure(
        CallHashFailure(
            kind = CallHashErrorKind.TransientNetwork,
            phase = CallHashPhase.OAuth,
            message = "timeout",
        ),
    )

    private fun assertNotConnected(state: ConnectionSnapshot) {
        assertNotEquals(RecoveryPhase.Connected, state.recovery.phase)
        assertNotEquals(ConnState.Connected, state.ui.connState)
        assertNotEquals(ConnectionUiPhase.Connected, state.ui.phase)
        assertTrue(state.intent.wantsConnected)
        assertNotEquals(TransportLifecycle.Running, state.transport)
    }

    @Test
    fun runningBypassThreeIoExceptionsNeverReturnsConnected() {
        var state = connectBypass()
        assertEquals(RecoveryPhase.Connected, state.recovery.phase)
        assertEquals(TransportLifecycle.Running, state.transport)

        var r = begin(state, 20L)
        state = r.state
        assertEquals(CallCreateOp.InFlight, state.call.createOp)
        assertEquals(CallValidity.ConfirmedDead, state.call.validity)
        assertNotConnected(state)
        assertEquals(ConnectionUiPhase.Recovering, state.ui.phase)
        assertTrue(state.ui.message.contains("Обновляю звонок"))

        repeat(2) { attempt ->
            r = applyOutcome(state, ioFailure(), 30L + attempt)
            state = r.state
            assertEquals(CallCreateOp.Backoff, state.call.createOp)
            assertEquals(CallValidity.ConfirmedDead, state.call.validity)
            assertTrue(r.command is RecoveryCommand.ScheduleRetry)
            assertNotConnected(state)
            val clock = reduce(
                state,
                ConnectionEvent.Clock(state.recovery.nextRetryAtElapsedMs!!),
                state.recovery.nextRetryAtElapsedMs!!,
            )
            assertEquals(RecoveryCommand.ResumeCallCreate, clock.command)
            assertEquals(CallCreateOp.InFlight, clock.state.call.createOp)
            assertNotConnected(clock.state)
            state = clock.state
        }

        r = applyOutcome(state, ioFailure(), 90L)
        state = r.state
        assertEquals(CallCreateOp.NeedsUser, state.call.createOp)
        assertEquals(UserActionKind.CallDead, state.call.createUserAction)
        assertEquals(RecoveryPhase.NeedsUserAction, state.recovery.phase)
        assertEquals(ConnState.NeedsUserAction, state.ui.connState)
        assertEquals(RecoveryCommand.ReleaseCallHold, r.command)
        assertNotConnected(state)

        val clock = reduce(state, ConnectionEvent.Clock(5_000L), 5_000L)
        assertEquals(RecoveryPhase.NeedsUserAction, clock.state.recovery.phase)
        assertEquals(ConnState.NeedsUserAction, clock.state.ui.connState)
        assertNotEquals(RecoveryCommand.ResumeCallCreate, clock.command)
        val underlay = reduce(state, ConnectionEvent.UnderlayUpdated(usableCellular(2L)), 6_000L)
        assertEquals(RecoveryPhase.NeedsUserAction, underlay.state.recovery.phase)
        assertEquals(UserActionKind.CallDead, underlay.state.call.createUserAction)
        assertNotConnected(underlay.state)
    }

    @Test
    fun startingBypassDoesNotStayConnectingAfterBudget() {
        var state = connectBypass(confirm = false)
        assertEquals(TransportLifecycle.Starting, state.transport)
        assertEquals(RecoveryPhase.ConnectingBypass, state.recovery.phase)

        state = begin(state, 20L).state
        assertNotEquals(RecoveryPhase.ConnectingBypass, state.recovery.phase)
        assertEquals(TransportLifecycle.Failed, state.transport)

        repeat(2) {
            state = applyOutcome(state, ioFailure(), 40L).state
            state = reduce(
                state,
                ConnectionEvent.Clock(state.recovery.nextRetryAtElapsedMs!!),
                state.recovery.nextRetryAtElapsedMs!!,
            ).state
        }
        val done = applyOutcome(state, ioFailure(), 80L)
        assertEquals(CallCreateOp.NeedsUser, done.state.call.createOp)
        assertNotEquals(RecoveryPhase.ConnectingBypass, done.state.recovery.phase)
        assertNotEquals(ConnState.Connecting, done.state.ui.connState)
        assertNotConnected(done.state)
    }

    @Test
    fun autoLteDeadRecreateCanUseDirectInsteadOfFalseRunning() {
        var state = connectBypass(mode = ConnPathMode.Auto)
        assertEquals(RecoveryPhase.Connected, state.recovery.phase)
        state = begin(state, 20L).state
        assertNotConnected(state)
        val done = applyOutcome(
            state,
            CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.InvalidResponse,
                    phase = CallHashPhase.Parse,
                    message = INVALID_RESPONSE_MESSAGE,
                ),
            ),
            30L,
        )
        assertNotConnected(done.state)
        assertNotEquals(TransportLifecycle.Running, done.state.transport)
        assertTrue(
            done.command is RecoveryCommand.StartDirect ||
                done.command is RecoveryCommand.ParkBypassForDirect ||
                done.state.recovery.phase == RecoveryPhase.NeedsUserAction,
        )
        if (done.command is RecoveryCommand.StartDirect ||
            done.command is RecoveryCommand.ParkBypassForDirect
        ) {
            assertNotEquals(CallCreateOp.NeedsUser, done.state.call.createOp)
        }
    }

    @Test
    fun captchaSurvivesClockAndIsNotSignIn() {
        var state = connectBypass()
        state = begin(state, 20L).state
        val r = applyOutcome(
            state,
            CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.Captcha,
                    phase = CallHashPhase.CallsStart,
                    message = "Captcha needed",
                    apiCode = 14,
                ),
            ),
            30L,
        )
        assertEquals(CallCreateOp.NeedsUser, r.state.call.createOp)
        assertEquals(UserActionKind.Captcha, r.state.call.createUserAction)
        assertEquals(CallValidity.ConfirmedDead, r.state.call.validity)
        assertTrue(r.state.ui.actions.contains(ConnectionUiAction.PassCaptcha))
        assertFalse(r.state.ui.actions.contains(ConnectionUiAction.SignIn))
        val clock = reduce(r.state, ConnectionEvent.Clock(8_000L), 8_000L)
        assertEquals(UserActionKind.Captcha, clock.state.call.createUserAction)
        assertTrue(clock.state.ui.actions.contains(ConnectionUiAction.PassCaptcha))
        assertNotConnected(clock.state)
        val underlay = reduce(r.state, ConnectionEvent.UnderlayUpdated(usableCellular(3L)), 9_000L)
        assertEquals(UserActionKind.Captcha, underlay.state.call.createUserAction)
        assertTrue(underlay.state.ui.actions.contains(ConnectionUiAction.PassCaptcha))
    }

    @Test
    fun authRequiredStaysSignInAfterUnderlay() {
        var state = connectBypass()
        state = begin(state, 20L).state
        val r = applyOutcome(
            state,
            CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.AuthRequired,
                    phase = CallHashPhase.OAuth,
                    message = AUTH_REQUIRED_MESSAGE,
                ),
            ),
            30L,
        )
        assertEquals(UserActionKind.SignIn, r.state.call.createUserAction)
        assertEquals(CallValidity.NeedsAuth, r.state.call.validity)
        assertTrue(r.state.ui.actions.contains(ConnectionUiAction.SignIn))
        val underlay = reduce(r.state, ConnectionEvent.UnderlayUpdated(usableCellular(4L)), 40L)
        assertEquals(UserActionKind.SignIn, underlay.state.call.createUserAction)
        assertEquals(CallValidity.NeedsAuth, underlay.state.call.validity)
        assertNotConnected(underlay.state)
    }

    @Test
    fun networkLossDuringBackoffDoesNotHttpUntilOneResume() {
        var httpCalls = 0
        fun outcome(): CallHashOutcome {
            httpCalls++
            return ioFailure()
        }

        var state = connectBypass()
        state = begin(state, 20L).state
        httpCalls = 0
        var r = applyOutcome(state, outcome(), 30L)
        assertEquals(1, httpCalls)
        assertEquals(1, r.state.call.createNetworkAttempts)
        assertEquals(CallCreateOp.Backoff, r.state.call.createOp)

        val lost = reduce(r.state, ConnectionEvent.UnderlayUpdated(noNetwork()), 40L)
        assertEquals(CallCreateOp.WaitingNetwork, lost.state.call.createOp)
        assertEquals(1, lost.state.call.createNetworkAttempts)
        assertEquals(RecoveryCommand.PauseNetOps, lost.command)
        assertEquals(CallValidity.ConfirmedDead, lost.state.call.validity)

        val clock = reduce(lost.state, ConnectionEvent.Clock(60_000L), 60_000L)
        assertEquals(CallCreateOp.WaitingNetwork, clock.state.call.createOp)
        assertNotEquals(RecoveryCommand.ResumeCallCreate, clock.command)
        assertEquals(1, httpCalls)

        val back = reduce(
            lost.state,
            ConnectionEvent.UnderlayUpdated(usableCellular(5L)),
            61_000L,
        )
        assertEquals(RecoveryCommand.ResumeCallCreate, back.command)
        assertEquals(CallCreateOp.InFlight, back.state.call.createOp)
        assertEquals(1, back.state.call.createNetworkAttempts)

        val second = applyOutcome(back.state, outcome(), 62_000L)
        assertEquals(2, httpCalls)
        assertEquals(2, second.state.call.createNetworkAttempts)
    }

    @Test
    fun stopDuringWaitDropsStaleOutcome() {
        var state = connectBypass()
        state = begin(state, 20L).state
        val stopped = reduce(state, ConnectionEvent.UserDisconnect, 25L)
        assertEquals(RecoveryCommand.StopAll, stopped.command)
        assertFalse(stopped.state.intent.wantsConnected)
        val event = callRecreateChangedFromOutcome(
            captured = identityOf(state),
            live = identityOf(stopped.state).copy(wantsConnected = false),
            outcome = CallHashOutcome.Success(hash),
            holdService = true,
            networkAttempts = 0,
            underlayAllowsOps = true,
        )
        assertEquals(null, event)
        assertEquals(CallCreateOp.None, stopped.state.call.createOp)
        assertNotEquals(RecoveryPhase.Connected, stopped.state.recovery.phase)
    }

    @Test
    fun successAppliesOnceAndConnectedOnlyAfterNewConfirm() {
        var state = connectBypass()
        val oldEpoch = state.transportEpoch
        state = begin(state, 20L).state
        assertTrue(state.transportEpoch > oldEpoch)
        val applied = applyOutcome(state, CallHashOutcome.Success(hash), 30L)
        assertEquals(CallCreateOp.Applied, applied.state.call.createOp)
        assertEquals(RecoveryCommand.ReleaseCallHold, applied.command)
        assertEquals(CallValidity.Valid, applied.state.call.validity)
        assertNotConnected(applied.state)

        val stale = reduce(
            applied.state,
            ConnectionEvent.BypassConfirmed(
                sessionEpoch = applied.state.sessionEpoch,
                transportEpoch = oldEpoch,
                pathConfirmed = true,
                callEpoch = applied.state.call.callEpoch,
            ),
            40L,
        )
        assertNotEquals(RecoveryPhase.Connected, stale.state.recovery.phase)

        val ok = reduce(
            applied.state,
            ConnectionEvent.BypassConfirmed(
                sessionEpoch = applied.state.sessionEpoch,
                transportEpoch = applied.state.transportEpoch,
                pathConfirmed = true,
                callEpoch = applied.state.call.callEpoch,
            ),
            50L,
        )
        assertEquals(RecoveryPhase.Connected, ok.state.recovery.phase)
        assertEquals(CallCreateOp.None, ok.state.call.createOp)
        assertEquals(ConnState.Connected, ok.state.ui.connState)
    }

    @Test
    fun http503FailureRetriesThroughReducer() {
        val outcome = CallHashOutcome.Failure(
            CallHashFailure(
                kind = CallHashErrorKind.TransientNetwork,
                phase = CallHashPhase.OAuth,
                message = TRANSIENT_NETWORK_MESSAGE,
                httpCode = 503,
                retryAfterMs = 5_000L,
            ),
        )
        var state = connectBypass()
        state = begin(state, 20L).state
        val r = applyOutcome(state, outcome, 30L)
        assertEquals(CallCreateOp.Backoff, r.state.call.createOp)
        assertTrue(r.command is RecoveryCommand.ScheduleRetry)
        assertTrue((r.command as RecoveryCommand.ScheduleRetry).delayMs >= 5_000L)
        assertNotConnected(r.state)
        assertEquals(CallValidity.ConfirmedDead, r.state.call.validity)
    }

    /**
     * Manager: saveCallHash → CallIdentityChanged → StartBypass(reuse) →
     * CallRecreateChanged(Applied). Applied must not freeze transport recovery.
     */
    private fun applySuccessfulRecreateLikeManager(
        inFlight: ConnectionSnapshot,
        elapsedMs: Long,
    ): ReduceResult {
        val captured = identityOf(inFlight)
        val saved = reduce(
            inFlight,
            ConnectionEvent.CallIdentityChanged(
                profileId = inFlight.intent.profileId,
                hashPresent = true,
                identityToken = "new-hash-r7",
            ),
            elapsedMs,
        )
        val discard = saved.command as RecoveryCommand.DiscardStaleCall
        assertTrue(
            "saveCallHash must start Bypass with the new hash, not calls.start",
            discard.then is RecoveryCommand.StartBypass &&
                (discard.then as RecoveryCommand.StartBypass).reuseCall,
        )
        assertTrue(saved.state.call.callEpoch > inFlight.call.callEpoch)
        val event = callRecreateChangedFromOutcome(
            captured = captured,
            live = captured,
            outcome = CallHashOutcome.Success(hash),
            holdService = inFlight.call.createHold,
            networkAttempts = inFlight.call.createNetworkAttempts,
            underlayAllowsOps = true,
        )
        assertTrue(event != null)
        assertEquals(CallCreateOp.Applied, event!!.op)
        val applied = reduce(saved.state, event, elapsedMs + 1L)
        assertEquals(RecoveryCommand.ReleaseCallHold, applied.command)
        assertNotEquals(
            "Applied must not issue a second calls.start",
            RecoveryCommand.ResumeCallCreate,
            applied.command,
        )
        return applied
    }

    private fun assertTransportRecoveryNotFrozen(result: ReduceResult) {
        assertNotEquals(RecoveryCommand.None, result.command)
        assertNotEquals(RecoveryCommand.ResumeCallCreate, result.command)
        assertTrue(
            result.command is RecoveryCommand.StartBypass ||
                result.command is RecoveryCommand.ScheduleRetry,
        )
        if (result.command is RecoveryCommand.StartBypass) {
            assertTrue((result.command as RecoveryCommand.StartBypass).reuseCall)
        }
        assertNotEquals(RecoveryPhase.Connected, result.state.recovery.phase)
        if (result.state.recovery.phase == RecoveryPhase.ConnectingBypass) {
            assertEquals(TransportLifecycle.Starting, result.state.transport)
        }
        assertNotEquals(ConnState.Connected, result.state.ui.connState)
    }

    @Test
    fun appliedThenBypassFailedRecoversWithSameHash() {
        var state = connectBypass()
        state = begin(state, 20L).state
        val applied = applySuccessfulRecreateLikeManager(state, 30L)
        assertEquals(CallCreateOp.Applied, applied.state.call.createOp)
        assertNotConnected(applied.state)

        val failed = reduce(
            applied.state,
            ConnectionEvent.BypassFailed(
                sessionEpoch = applied.state.sessionEpoch,
                transportEpoch = applied.state.transportEpoch,
                reason = "transport failed",
                callEpoch = applied.state.call.callEpoch,
            ),
            40L,
        )
        assertTransportRecoveryNotFrozen(failed)
        assertTrue(failed.state.recovery.permit.netOpsAllowed)
        assertEquals("new-hash-r7", failed.state.call.identityToken)

        val staleConfirm = reduce(
            failed.state,
            ConnectionEvent.BypassConfirmed(
                sessionEpoch = applied.state.sessionEpoch,
                transportEpoch = applied.state.transportEpoch,
                pathConfirmed = true,
                callEpoch = state.call.callEpoch,
            ),
            50L,
        )
        assertNotEquals(RecoveryPhase.Connected, staleConfirm.state.recovery.phase)
    }

    @Test
    fun appliedThenTransportDiedRecoversWithSameHash() {
        var state = connectBypass()
        state = begin(state, 20L).state
        val applied = applySuccessfulRecreateLikeManager(state, 30L)
        val died = reduce(
            applied.state,
            ConnectionEvent.TransportDied(
                sessionEpoch = applied.state.sessionEpoch,
                transportEpoch = applied.state.transportEpoch,
                path = VpnPath.Bypass,
            ),
            40L,
        )
        assertTransportRecoveryNotFrozen(died)
        assertTrue(died.state.recovery.permit.netOpsAllowed)
        assertNotEquals(RecoveryCommand.ResumeCallCreate, died.command)
    }

    @Test
    fun appliedNetworkLossThenReturnRestoresPermit() {
        var state = connectBypass()
        state = begin(state, 20L).state
        val applied = applySuccessfulRecreateLikeManager(state, 30L)
        val lost = reduce(
            applied.state,
            ConnectionEvent.UnderlayUpdated(noNetwork(11L)),
            40L,
        )
        assertFalse(lost.state.recovery.permit.netOpsAllowed)
        assertEquals(RecoveryCommand.PauseNetOps, lost.command)
        assertNotEquals(RecoveryPhase.Connected, lost.state.recovery.phase)

        val restored = reduce(
            lost.state,
            ConnectionEvent.UnderlayUpdated(usableCellular(12L)),
            50L,
        )
        assertTrue(restored.state.recovery.permit.netOpsAllowed)
        assertNotEquals(RecoveryCommand.ResumeCallCreate, restored.command)
        if (restored.command is RecoveryCommand.StartBypass) {
            assertTrue((restored.command as RecoveryCommand.StartBypass).reuseCall)
        } else if (restored.command is RecoveryCommand.None) {
            assertEquals(TransportLifecycle.Starting, restored.state.transport)
        } else {
            assertTrue(
                restored.command is RecoveryCommand.ScheduleRetry ||
                    restored.command is RecoveryCommand.PauseNetOps,
            )
        }
        assertNotEquals(ConnState.Connected, restored.state.ui.connState)
        if (restored.state.recovery.phase == RecoveryPhase.ConnectingBypass) {
            assertTrue(
                restored.state.transport == TransportLifecycle.Starting ||
                    restored.command is RecoveryCommand.StartBypass ||
                    restored.command is RecoveryCommand.ScheduleRetry,
            )
        }
    }

    @Test
    fun needsUserManualDirectStartsIndependentPath() {
        var state = connectBypass()
        state = begin(state, 20L).state
        val needsUser = applyOutcome(
            state,
            CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.AuthRequired,
                    phase = CallHashPhase.OAuth,
                    message = AUTH_REQUIRED_MESSAGE,
                ),
            ),
            30L,
        )
        assertEquals(CallCreateOp.NeedsUser, needsUser.state.call.createOp)
        assertEquals(UserActionKind.SignIn, needsUser.state.call.createUserAction)

        val direct = reduce(
            needsUser.state,
            ConnectionEvent.PathModeChanged(ConnPathMode.Direct),
            40L,
        )
        assertTrue(
            "cmd=${direct.command} phase=${direct.state.recovery.phase}",
            direct.command is RecoveryCommand.StartDirect ||
                direct.command is RecoveryCommand.ParkBypassForDirect,
        )
        assertEquals(ConnPathMode.Direct, direct.state.intent.mode)
        assertTrue(
            direct.state.recovery.phase == RecoveryPhase.ConnectingDirect ||
                direct.state.recovery.phase == RecoveryPhase.SwitchingToWifi,
        )
        assertEquals(UserActionKind.SignIn, direct.state.call.createUserAction)
        assertEquals(CallValidity.NeedsAuth, direct.state.call.validity)
        assertNotEquals(RecoveryCommand.ResumeCallCreate, direct.command)
    }

    @Test
    fun autoNeedsUserOnLteStartsDirectWhenWifiAppears() {
        var state = connectBypass()
        state = begin(state, 20L).state
        val needsUser = applyOutcome(
            state,
            CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.Captcha,
                    phase = CallHashPhase.CallsStart,
                    message = "Captcha needed",
                    apiCode = 14,
                ),
            ),
            30L,
        )
        assertEquals(CallCreateOp.NeedsUser, needsUser.state.call.createOp)
        assertEquals(UserActionKind.Captcha, needsUser.state.call.createUserAction)

        val autoOnLte = reduce(
            needsUser.state,
            ConnectionEvent.PathModeChanged(ConnPathMode.Auto),
            35L,
        )
        val waiting = if (autoOnLte.state.call.createOp == CallCreateOp.NeedsUser) {
            autoOnLte.state
        } else {
            autoOnLte.state.copy(
                call = needsUser.state.call,
                recovery = needsUser.state.recovery.copy(
                    phase = RecoveryPhase.NeedsUserAction,
                    inFlight = false,
                ),
                transport = TransportLifecycle.Failed,
                activePath = VpnPath.Bypass,
                underlay = usableCellular(3L),
            )
        }
        val wifi = reduce(
            waiting,
            ConnectionEvent.UnderlayUpdated(usableWifi(4L)),
            40L,
        )
        assertTrue(
            "cmd=${wifi.command} phase=${wifi.state.recovery.phase} path=${wifi.state.activePath} " +
                "transport=${wifi.state.transport} underlay=${wifi.state.underlay.kind}",
            wifi.command is RecoveryCommand.StartDirect ||
                wifi.command is RecoveryCommand.ParkBypassForDirect,
        )
        assertEquals(UserActionKind.Captcha, wifi.state.call.createUserAction)
        assertNotEquals(RecoveryCommand.ResumeCallCreate, wifi.command)
        assertNotEquals(RecoveryPhase.NeedsUserAction, wifi.state.recovery.phase)
    }

    @Test
    fun lateRecreateCallbackDoesNotLeaveWorkingDirect() {
        var state = connectBypass()
        state = begin(state, 20L).state
        val inFlightIdentity = identityOf(state)
        val needsUser = applyOutcome(
            state,
            CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.AuthRequired,
                    phase = CallHashPhase.OAuth,
                    message = AUTH_REQUIRED_MESSAGE,
                ),
            ),
            30L,
        )
        val direct = reduce(
            needsUser.state,
            ConnectionEvent.PathModeChanged(ConnPathMode.Direct),
            40L,
        )
        val running = reduce(
            direct.state,
            ConnectionEvent.DirectConfirmed(
                sessionEpoch = direct.state.sessionEpoch,
                transportEpoch = direct.state.transportEpoch,
                networkKey = cellKey,
                pathConfirmed = true,
            ),
            50L,
        )
        assertEquals(RecoveryPhase.Connected, running.state.recovery.phase)
        assertEquals(VpnPath.Direct, running.state.activePath)
        assertEquals(TransportLifecycle.Running, running.state.transport)

        val late = callRecreateChangedFromOutcome(
            captured = inFlightIdentity,
            live = inFlightIdentity.copy(
                sessionEpoch = running.state.sessionEpoch,
                callEpoch = running.state.call.callEpoch,
                wantsConnected = true,
            ),
            outcome = CallHashOutcome.Success(hash),
            holdService = true,
            networkAttempts = 0,
            underlayAllowsOps = true,
        )
        assertTrue(late != null)
        val after = reduce(running.state, late!!, 60L)
        assertEquals(VpnPath.Direct, after.state.activePath)
        assertEquals(TransportLifecycle.Running, after.state.transport)
        assertEquals(RecoveryPhase.Connected, after.state.recovery.phase)
        assertNotEquals(RecoveryPhase.ConnectingBypass, after.state.recovery.phase)
        assertNotEquals(RecoveryCommand.StartBypass(reuseCall = true), after.command)
        assertEquals(UserActionKind.SignIn, after.state.call.createUserAction)
    }
}