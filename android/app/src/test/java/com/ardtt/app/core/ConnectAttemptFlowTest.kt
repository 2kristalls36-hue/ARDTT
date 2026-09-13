package com.ardtt.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectAttemptFlowTest {
    private val cell = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell", carrier = "25001")

    private fun ctx(
        ui: ConnState = ConnState.Idle,
        hasEvidence: Boolean = false,
        transportBusy: Boolean = false,
    ) = ConnectLaunchContext(
        entry = ConnectEntryPoint.Button,
        uiState = ui,
        mode = ConnPathMode.Auto,
        underlayKind = UnderlayKind.Cellular,
        underlayUsable = true,
        underlayKey = cell,
        profileId = "p",
        sessionEpoch = 0L,
        networkEpoch = 1L,
        hasSameNetworkProbeEvidence = hasEvidence,
        probeJobActive = false,
        transportStartingOrLive = transportBusy,
        hasCallHash = true,
    )

    private fun underlay() = UnderlaySnapshot(
        key = cell,
        kind = UnderlayKind.Cellular,
        availability = UnderlayAvailability.Usable,
        handle = 1L,
        simId = 11,
        cellularConnected = true,
        networkEpoch = 1L,
    )

    private fun positiveEvidence(seriesId: String) = ReachabilityEvidence(
        networkKey = cell,
        originNetworkKey = cell,
        profileId = "p",
        yandex = CheckOutcome.Success,
        bigtech = CheckOutcome.Timeout,
        google = CheckOutcome.Timeout,
        ruService = CheckOutcome.Success,
        seriesId = seriesId,
    )

    private fun ignoreEvidence(seriesId: String) = ReachabilityEvidence(
        networkKey = cell,
        originNetworkKey = cell,
        profileId = "p",
        yandex = CheckOutcome.Timeout,
        seriesId = seriesId,
    )

    @Test
    fun reentrantWaiterSeesFoldedPositiveBeforeRouteSelection() = runBlocking {
        val events = mutableListOf<String>()
        val commands = mutableListOf<RecoveryCommand>()
        var snapshot = ConnectionSnapshot(underlay = underlay())
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "pos",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        val waiter = launch(Dispatchers.Unconfined) {
            c.awaitDecision(wait.requestId)
            events += "connect_route_selected"
            val continued = c.continueAfterWait(
                ctx(ui = ConnState.Probing),
                wait.requestId,
            )
            assertTrue(continued is ConnectLaunchAction.Proceed)
            val reduced = ConnectionReducer.reduce(
                snapshot,
                ConnectionEvent.UserConnect(
                    ConnPathMode.Auto,
                    "p",
                    hasCallHash = true,
                    silentRecreate = false,
                    inheritProbeSessionEpoch = (continued as ConnectLaunchAction.Proceed).inheritProbeSessionEpoch,
                ),
                20L,
            )
            commands += reduced.command
        }
        c.admitAndApplyFinal(
            ProbeCallback(
                seriesId = "pos",
                sessionEpoch = 0L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = false,
                sample = RestrictionSample.Positive,
                wantsConnected = false,
                uiState = ConnState.Probing,
                liveNetworkKey = cell,
                liveProfileId = "p",
                userStop = false,
            ),
        ) {
            snapshot = snapshot.copy(
                evidence = foldReachabilityEvidence(
                    previous = snapshot.evidence,
                    incoming = positiveEvidence("pos"),
                    cellular = true,
                    elapsedMs = 10L,
                ),
            )
            events += "final_applied"
        }
        waiter.join()
        assertEquals(listOf("final_applied", "connect_route_selected"), events)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is RecoveryCommand.StartBypass)
        assertFalse(commands[0] is RecoveryCommand.StartDirect)
    }

    @Test
    fun queuedWaiterUnknownWithoutEarlyStartsDirect() = runBlocking {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "unk",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        var snapshot = ConnectionSnapshot(underlay = underlay())
        c.admitAndApplyFinal(
            ProbeCallback(
                seriesId = "unk",
                sessionEpoch = 0L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = false,
                sample = RestrictionSample.Ignore,
                wantsConnected = false,
                uiState = ConnState.Probing,
                liveNetworkKey = cell,
                liveProfileId = "p",
                userStop = false,
            ),
        ) {
            snapshot = snapshot.copy(
                evidence = foldReachabilityEvidence(
                    previous = snapshot.evidence,
                    incoming = ignoreEvidence("unk"),
                    cellular = true,
                    elapsedMs = 10L,
                ),
            )
        }
        val proceeded = c.continueAfterWait(ctx(ui = ConnState.Probing), wait.requestId)
            as ConnectLaunchAction.Proceed
        val reduced = ConnectionReducer.reduce(
            snapshot,
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", hasCallHash = true, silentRecreate = false),
            20L,
        )
        assertTrue(reduced.command is RecoveryCommand.StartDirect)
        assertEquals(
            ConnectLaunchAction.Ignore,
            c.continueAfterWait(ctx(ui = ConnState.Probing), wait.requestId),
        )
        c.markLaunched(proceeded.requestId)
        assertEquals(
            ConnectLaunchAction.Ignore,
            c.onConnectRequested(ctx(ui = ConnState.Connecting, transportBusy = true)),
        )
    }

    @Test
    fun ordinarySuccessReleasesWaitBeforeFinalIsHeld() = runBlocking {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "ord",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        val early = c.admitEarly(
            ProbeCallback(
                seriesId = "ord",
                sessionEpoch = 0L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = true,
                sample = RestrictionSample.Open,
                wantsConnected = false,
                uiState = ConnState.Probing,
                liveNetworkKey = cell,
                liveProfileId = "p",
                userStop = false,
            ),
        )
        assertTrue(early.completeWait)
        val proceeded = c.continueAfterWait(ctx(ui = ConnState.Probing), wait.requestId)
        assertTrue(proceeded is ConnectLaunchAction.Proceed)
        c.markLaunched((proceeded as ConnectLaunchAction.Proceed).requestId)
        val finalAdmit = c.admitAndApplyFinal(
            ProbeCallback(
                seriesId = "ord",
                sessionEpoch = 0L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = true,
                sample = RestrictionSample.Open,
                wantsConnected = true,
                uiState = ConnState.Connecting,
                liveNetworkKey = cell,
                liveProfileId = "p",
                userStop = false,
            ),
        ) {}
        assertTrue(finalAdmit.accepted)
        assertEquals(
            ConnectLaunchAction.Ignore,
            c.continueAfterWait(ctx(ui = ConnState.Connecting, transportBusy = true), wait.requestId),
        )
    }

    @Test
    fun fourBackgroundUnknownsAfterCachedReconnectUpdateStreak() {
        val c = ConnectRequestCoordinator()
        val first = c.onConnectRequested(ctx(hasEvidence = true)) as ConnectLaunchAction.Proceed
        c.markLaunched(first.requestId)
        c.revokeConnectWork()
        val again = c.onConnectRequested(ctx(ui = ConnState.Ready, hasEvidence = true))
            as ConnectLaunchAction.Proceed
        c.markLaunched(again.requestId)
        var evidence: ReachabilityEvidence? = positiveEvidence("seed").let {
            foldReachabilityEvidence(null, it, cellular = true, elapsedMs = 10L)
        }
        repeat(4) { i ->
            val series = "u$i"
            c.registerProbe(
                role = ProbeRole.BackgroundDiagnostic,
                seriesId = series,
                sessionEpoch = 5L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                requestId = again.requestId,
            )
            val admit = c.admitAndApplyFinal(
                ProbeCallback(
                    seriesId = series,
                    sessionEpoch = 5L,
                    networkEpoch = 1L,
                    profileId = "p",
                    networkKey = cell,
                    ordinarySuccess = false,
                    sample = RestrictionSample.Ignore,
                    wantsConnected = true,
                    uiState = ConnState.Connected,
                    liveNetworkKey = cell,
                    liveProfileId = "p",
                    userStop = false,
                ),
            ) { admitted ->
                assertTrue(admitted.applyToReducer)
                evidence = foldReachabilityEvidence(
                    previous = evidence,
                    incoming = ignoreEvidence(series),
                    cellular = true,
                    elapsedMs = 10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS + i,
                )
            }
            assertTrue(admit.accepted)
        }
        assertEquals(4, evidence!!.unknownStreak)
        assertFalse(
            RestrictionScore.bypassHoldsDirectReeval(
                historicalScore = evidence!!.historicalWhitelistScore(cell, "p"),
                freshStrong = evidence!!.hasFreshStrong(
                    10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS + 4,
                    cell,
                    "p",
                ),
                usable = evidence!!.usableAt(
                    10L + RecoverySettings.PROBE_RESTRICTION_TTL_MS + 4,
                    cell,
                    "p",
                ),
                unknownStreak = evidence!!.unknownStreak,
                alreadyBypass = true,
            ),
        )
    }

    @Test
    fun leftoverStartingAfterErrorDoesNotBlockTheNextUserConnect() {
        val started = ConnectionReducer.reduce(
            ConnectionSnapshot(underlay = underlay()),
            ConnectionEvent.UserConnect(
                ConnPathMode.Auto,
                "p",
                hasCallHash = true,
                silentRecreate = false,
            ),
            10L,
        )
        assertTrue(started.command is RecoveryCommand.StartDirect)
        assertTrue(started.state.intent.wantsConnected)
        assertTrue(started.state.recovery.inFlight)
        assertEquals(TransportLifecycle.Starting, started.state.transport)

        val withoutAbort = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.UnderlayUpdated(underlay()),
            20L,
        )
        assertEquals(RecoveryCommand.None, withoutAbort.command)
        assertEquals(ConnState.Connecting, withoutAbort.state.ui.connState)
        assertTrue(withoutAbort.state.intent.wantsConnected)

        val failed = ConnectionReducer.reduce(
            started.state,
            ConnectionEvent.AttemptFailed("В профиле нет ключей AWG"),
            21L,
        )
        assertEquals(RecoveryCommand.StopAll, failed.command)
        assertFalse(failed.state.intent.wantsConnected)
        assertFalse(failed.state.recovery.inFlight)
        assertEquals(TransportLifecycle.Stopped, failed.state.transport)
        assertEquals(ConnState.Error, failed.state.ui.connState)

        val afterUnderlay = ConnectionReducer.reduce(
            failed.state,
            ConnectionEvent.UnderlayUpdated(underlay()),
            22L,
        )
        assertEquals(RecoveryCommand.None, afterUnderlay.command)
        assertEquals(ConnState.Error, afterUnderlay.state.ui.connState)
        assertFalse(afterUnderlay.state.intent.wantsConnected)

        assertTrue(needsFreshUserConnect(ConnState.Error, failed.state.intent.wantsConnected))
        val retry = ConnectionReducer.reduce(
            failed.state,
            ConnectionEvent.UserConnect(
                ConnPathMode.Auto,
                "p",
                hasCallHash = true,
                silentRecreate = false,
            ),
            23L,
        )
        assertTrue(retry.command is RecoveryCommand.StartDirect)
        assertTrue(retry.state.intent.wantsConnected)
        assertTrue(retry.state.recovery.inFlight)
    }

    @Test
    fun finishAttemptThenLateFinalDoesNotSelectANewRoute() {
        val c = ConnectRequestCoordinator()
        val proceed = c.onConnectRequested(ctx(hasEvidence = true)) as ConnectLaunchAction.Proceed
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "late-err",
            sessionEpoch = 2L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = proceed.requestId,
        )
        c.markLaunched(proceed.requestId)
        var snapshot = ConnectionReducer.reduce(
            ConnectionSnapshot(underlay = underlay()),
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", hasCallHash = true, silentRecreate = false),
            10L,
        ).state
        c.finishAttempt()
        snapshot = ConnectionReducer.reduce(
            snapshot,
            ConnectionEvent.AttemptFailed("В профиле нет ключей AWG"),
            11L,
        ).state
        val commands = mutableListOf<RecoveryCommand>()
        c.admitAndApplyFinal(
            ProbeCallback(
                seriesId = "late-err",
                sessionEpoch = 2L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = false,
                sample = RestrictionSample.Positive,
                wantsConnected = true,
                uiState = ConnState.Error,
                liveNetworkKey = cell,
                liveProfileId = "p",
                userStop = false,
            ),
        ) { admitted ->
            assertTrue(admitted.idleFold)
            assertFalse(admitted.applyToReducer)
            snapshot = snapshot.copy(
                evidence = foldReachabilityEvidence(
                    previous = snapshot.evidence,
                    incoming = positiveEvidence("late-err"),
                    cellular = true,
                    elapsedMs = 12L,
                ),
            )
        }
        val after = ConnectionReducer.reduce(
            snapshot,
            ConnectionEvent.UnderlayUpdated(underlay()),
            13L,
        )
        commands += after.command
        assertEquals(RecoveryCommand.None, after.command)
        assertEquals(ConnState.Error, after.state.ui.connState)
        assertTrue(snapshot.evidence?.hasFreshStrong(12L, cell, "p") == true)
        assertTrue(needsFreshUserConnect(ConnState.Error, snapshot.intent.wantsConnected))
        val retry = ConnectionReducer.reduce(
            snapshot,
            ConnectionEvent.UserConnect(ConnPathMode.Auto, "p", hasCallHash = true, silentRecreate = false),
            14L,
        )
        assertTrue(retry.command is RecoveryCommand.StartBypass)
        assertEquals(1, commands.size)
    }
}
