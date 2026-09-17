package com.ardtt.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRequestCoordinatorTest {
    private val cell = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell", carrier = "25001")

    private fun ctx(
        entry: ConnectEntryPoint = ConnectEntryPoint.Widget,
        ui: ConnState = ConnState.Idle,
        probeActive: Boolean = false,
        hasEvidence: Boolean = false,
        transportBusy: Boolean = false,
        profileId: String? = "p",
        key: NetworkKey? = cell,
        mode: ConnPathMode = ConnPathMode.Auto,
    ) = ConnectLaunchContext(
        entry = entry,
        uiState = ui,
        mode = mode,
        underlayKind = UnderlayKind.Cellular,
        underlayUsable = true,
        underlayKey = key,
        profileId = profileId,
        sessionEpoch = 0L,
        networkEpoch = 1L,
        hasSameNetworkProbeEvidence = hasEvidence,
        probeJobActive = probeActive,
        transportStartingOrLive = transportBusy,
        hasCallHash = true,
    )

    private fun callback(
        seriesId: String,
        ordinary: Boolean,
        sample: RestrictionSample,
        wantsConnected: Boolean = false,
        ui: ConnState = ConnState.Probing,
        userStop: Boolean = false,
        sessionEpoch: Long = 0L,
    ) = ProbeCallback(
        seriesId = seriesId,
        sessionEpoch = sessionEpoch,
        networkEpoch = 1L,
        profileId = "p",
        networkKey = cell,
        ordinarySuccess = ordinary,
        sample = sample,
        wantsConnected = wantsConnected,
        uiState = ui,
        liveNetworkKey = cell,
        liveProfileId = "p",
        userStop = userStop,
    )

    @Test
    fun widgetAndButtonShareOneWaitAndOneProceed() = runBlocking {
        val c = ConnectRequestCoordinator()
        val first = c.onConnectRequested(ctx(entry = ConnectEntryPoint.Widget))
        val wait = first as ConnectLaunchAction.EnqueueWait
        assertTrue(wait.startProbe)
        assertFalse(wait.alreadyWaiting)
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "s1",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        val second = c.onConnectRequested(ctx(entry = ConnectEntryPoint.Button, ui = ConnState.Probing, probeActive = true))
        val again = second as ConnectLaunchAction.EnqueueWait
        assertEquals(wait.requestId, again.requestId)
        assertTrue(again.alreadyWaiting)
        assertFalse(again.startProbe)

        val yandex = c.admitEarly(
            callback("s1", ordinary = false, sample = RestrictionSample.Ignore, userStop = true),
        )
        assertTrue(yandex.accepted)
        assertFalse(yandex.completeWait)

        val early = c.admitEarly(
            callback("s1", ordinary = true, sample = RestrictionSample.Open, userStop = true),
        )
        assertTrue(early.completeWait)
        assertFalse(early.applyToReducer)
        val proceeded = c.continueAfterWait(
            ctx(ui = ConnState.Probing, probeActive = true),
            wait.requestId,
        ) as ConnectLaunchAction.Proceed
        assertEquals(wait.requestId, proceeded.requestId)
        c.markLaunched(wait.requestId)

        val finalAdmit = c.admitFinal(
            callback("s1", ordinary = true, sample = RestrictionSample.Open, wantsConnected = true, ui = ConnState.Connecting),
        )
        assertTrue(finalAdmit.accepted)
        assertTrue(finalAdmit.applyToReducer)
        assertEquals(
            ConnectLaunchAction.Ignore,
            c.continueAfterWait(ctx(ui = ConnState.Connecting, probeActive = true, transportBusy = true), wait.requestId),
        )
        assertEquals(
            ConnectLaunchAction.Ignore,
            c.onConnectRequested(ctx(entry = ConnectEntryPoint.Button, ui = ConnState.Probing, probeActive = true)),
        )
    }

    @Test
    fun unknownFinalWithoutEarlyStillProceedsOnce() = runBlocking {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "u1",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        val finalAdmit = c.admitAndApplyFinal(
            callback("u1", ordinary = false, sample = RestrictionSample.Ignore, userStop = true),
        ) {}
        assertTrue(finalAdmit.completeWait)
        assertFalse(finalAdmit.applyToReducer)
        val proceeded = c.continueAfterWait(
            ctx(ui = ConnState.Probing, probeActive = true),
            wait.requestId,
        )
        assertTrue(proceeded is ConnectLaunchAction.Proceed)
        assertEquals(
            ConnectLaunchAction.Ignore,
            c.continueAfterWait(ctx(ui = ConnState.Probing, probeActive = true), wait.requestId),
        )
    }

    @Test
    fun stopRevokesLateEarlyAndFinal() {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "late",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        val revoke = c.revokeConnectWork()
        assertTrue(revoke.cancelProbe)
        assertTrue(c.lateCallbackBlocked("late"))
        assertEquals(
            ConnectLaunchAction.Ignore,
            c.continueAfterWait(ctx(ui = ConnState.Disconnecting, probeActive = true), wait.requestId),
        )
        val early = c.admitEarly(
            callback("late", ordinary = true, sample = RestrictionSample.Open, userStop = true, ui = ConnState.Disconnecting),
        )
        assertFalse(early.accepted)
        val finalAdmit = c.admitFinal(
            callback("late", ordinary = true, sample = RestrictionSample.Open, wantsConnected = true, userStop = true, ui = ConnState.Disconnecting),
        )
        assertFalse(finalAdmit.accepted)
        assertFalse(finalAdmit.idleFold)
        assertFalse(finalAdmit.allowReadyUi)
        assertFalse(finalAdmit.applyToReducer)
    }

    @Test
    fun newRequestDoesNotCompleteFromOldSeries() = runBlocking {
        val c = ConnectRequestCoordinator()
        val first = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "old",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = first.requestId,
        )
        c.revokeConnectWork()
        val second = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "new",
            sessionEpoch = 2L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = second.requestId,
        )
        val late = c.admitFinal(
            callback("old", ordinary = true, sample = RestrictionSample.Open, sessionEpoch = 0L),
        )
        assertFalse(late.accepted)
        assertEquals(second.requestId, c.activeRequestId())
        val own = c.admitAndApplyFinal(
            ProbeCallback(
                seriesId = "new",
                sessionEpoch = 2L,
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
        ) {}
        assertTrue(own.accepted)
        c.awaitDecision(second.requestId)
    }

    @Test
    fun profileOrNetworkChangeDropsContinuation() {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "s",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        c.admitFinal(callback("s", ordinary = false, sample = RestrictionSample.Ignore))
        val otherNet = NetworkKey(9L, UnderlayKind.Cellular, 11, "other", carrier = "25001")
        assertEquals(
            ConnectLaunchAction.Ignore,
            c.continueAfterWait(ctx(key = otherNet, ui = ConnState.Probing, probeActive = true), wait.requestId),
        )
        val c2 = ConnectRequestCoordinator()
        val wait2 = c2.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c2.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "s2",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait2.requestId,
        )
        c2.admitAndApplyFinal(callback("s2", ordinary = false, sample = RestrictionSample.Ignore)) {}
        assertEquals(
            ConnectLaunchAction.Ignore,
            c2.continueAfterWait(
                ctx(profileId = "q", ui = ConnState.Probing, probeActive = true),
                wait2.requestId,
            ),
        )
        val next = c2.onConnectRequested(ctx(profileId = "q"))
        assertTrue(next is ConnectLaunchAction.EnqueueWait || next is ConnectLaunchAction.Proceed)
    }

    @Test
    fun idleDiagnosticFinalIsNotACancelledConnect() {
        val c = ConnectRequestCoordinator()
        c.registerProbe(
            role = ProbeRole.IdleDiagnostic,
            seriesId = "idle",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
        )
        val ok = c.admitFinal(
            callback("idle", ordinary = false, sample = RestrictionSample.Ignore, userStop = true),
        )
        assertTrue(ok.accepted)
        assertTrue(ok.idleFold)
        assertTrue(ok.allowReadyUi)
        val dup = c.admitFinal(
            callback("idle", ordinary = false, sample = RestrictionSample.Ignore, userStop = true),
        )
        assertFalse(dup.accepted)
    }

    @Test
    fun revokedSeriesDoesNotDispatchEvenIfSessionStillWantsConnect() {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "owned",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        c.continueAfterWait(ctx(ui = ConnState.Probing, probeActive = true), wait.requestId)
        c.markLaunched(wait.requestId)
        c.revokeConnectWork()
        assertTrue(c.lateCallbackBlocked("owned"))
        val late = c.admitFinal(
            callback(
                "owned",
                ordinary = true,
                sample = RestrictionSample.Open,
                wantsConnected = true,
                ui = ConnState.Connecting,
            ),
        )
        assertFalse(late.accepted)
        assertFalse(late.applyToReducer)
        assertFalse(late.idleFold)
    }

    @Test
    fun duplicateFinalOfInheritedSeriesIsRejected() {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "owned",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        val first = c.admitFinal(
            callback("owned", ordinary = true, sample = RestrictionSample.Open, wantsConnected = true),
        )
        assertTrue(first.accepted)
        val dup = c.admitFinal(
            callback("owned", ordinary = true, sample = RestrictionSample.Open, wantsConnected = true),
        )
        assertFalse(dup.accepted)
    }

    @Test
    fun errorOrReadyAfterLaunchAllowsANewConnect() {
        val c = ConnectRequestCoordinator()
        val first = c.onConnectRequested(ctx(hasEvidence = true)) as ConnectLaunchAction.Proceed
        c.markLaunched(first.requestId)
        assertEquals(
            ConnectLaunchAction.Ignore,
            c.onConnectRequested(ctx(ui = ConnState.Connecting, transportBusy = true)),
        )
        val afterError = c.onConnectRequested(ctx(ui = ConnState.Error, hasEvidence = true))
        assertTrue(afterError is ConnectLaunchAction.Proceed)
        val secondId = (afterError as ConnectLaunchAction.Proceed).requestId
        assertTrue(secondId != first.requestId)
        c.markLaunched(secondId)
        c.finishAttempt()
        val afterReady = c.onConnectRequested(ctx(ui = ConnState.Ready, hasEvidence = true))
        assertTrue(afterReady is ConnectLaunchAction.Proceed)
    }

    @Test
    fun finishAttemptUnblocksInternalReconnect() {
        val c = ConnectRequestCoordinator()
        val first = c.onConnectRequested(ctx(hasEvidence = true)) as ConnectLaunchAction.Proceed
        c.markLaunched(first.requestId)
        c.finishAttempt()
        val again = c.onConnectRequested(ctx(ui = ConnState.Ready, hasEvidence = true))
        assertTrue(again is ConnectLaunchAction.Proceed)
    }

    @Test
    fun errorAndReadyNeedAFreshUserConnectEvenIfIntentStuck() {
        assertTrue(needsFreshUserConnect(ConnState.Error, wantsConnected = true))
        assertTrue(needsFreshUserConnect(ConnState.Ready, wantsConnected = true))
        assertTrue(needsFreshUserConnect(ConnState.Idle, wantsConnected = true))
        assertTrue(needsFreshUserConnect(ConnState.NeedsUserAction, wantsConnected = true))
        assertFalse(needsFreshUserConnect(ConnState.Connecting, wantsConnected = true))
        assertFalse(needsFreshUserConnect(ConnState.Connected, wantsConnected = true))
        assertTrue(needsFreshUserConnect(ConnState.Connecting, wantsConnected = false))
    }

    @Test
    fun finishAttemptIdleFoldsInitialFinalInsteadOfDrivingReducer() {
        val c = ConnectRequestCoordinator()
        val first = c.onConnectRequested(ctx(hasEvidence = true)) as ConnectLaunchAction.Proceed
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "late",
            sessionEpoch = 2L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = first.requestId,
        )
        c.markLaunched(first.requestId)
        c.finishAttempt()
        val admit = c.admitAndApplyFinal(
            ProbeCallback(
                seriesId = "late",
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
        ) {}
        assertTrue(admit.accepted)
        assertTrue(admit.idleFold)
        assertFalse(admit.applyToReducer)
        assertFalse(admit.completeWait)
    }

    @Test
    fun backgroundDiagnosticIsAcceptedAfterStopAndFreshReconnect() {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "init",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        c.admitAndApplyFinal(
            callback("init", ordinary = false, sample = RestrictionSample.Positive, wantsConnected = true),
        ) {}
        c.markLaunched(wait.requestId)
        c.revokeConnectWork()
        assertTrue(c.lateCallbackBlocked("init"))
        val reconnect = c.onConnectRequested(ctx(ui = ConnState.Ready, hasEvidence = true))
        val proceed = reconnect as ConnectLaunchAction.Proceed
        c.markLaunched(proceed.requestId)
        c.registerProbe(
            role = ProbeRole.BackgroundDiagnostic,
            seriesId = "bg",
            sessionEpoch = 4L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = proceed.requestId,
        )
        val bg = c.admitAndApplyFinal(
            ProbeCallback(
                seriesId = "bg",
                sessionEpoch = 4L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = false,
                sample = RestrictionSample.Positive,
                wantsConnected = true,
                uiState = ConnState.Connected,
                liveNetworkKey = cell,
                liveProfileId = "p",
                userStop = false,
            ),
        ) {}
        assertTrue(bg.accepted)
        assertTrue(bg.applyToReducer)
        assertFalse(c.lateCallbackBlocked("bg"))
        val lateOld = c.admitFinal(
            callback("init", ordinary = false, sample = RestrictionSample.Positive, wantsConnected = true),
        )
        assertFalse(lateOld.accepted)
    }

    @Test
    fun registeringBackgroundDoesNotRevokeInitialOwner() {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "init",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        c.registerProbe(
            role = ProbeRole.BackgroundDiagnostic,
            seriesId = "bg",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        val initFinal = c.admitFinal(
            callback("init", ordinary = false, sample = RestrictionSample.Ignore),
        )
        assertTrue(initFinal.accepted)
        val bg = c.admitFinal(
            ProbeCallback(
                seriesId = "bg",
                sessionEpoch = 0L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = false,
                sample = RestrictionSample.Ignore,
                wantsConnected = true,
                uiState = ConnState.Connecting,
                liveNetworkKey = cell,
                liveProfileId = "p",
                userStop = false,
            ),
        )
        assertTrue(bg.accepted)
        val dupInit = c.admitFinal(callback("init", ordinary = false, sample = RestrictionSample.Ignore))
        assertFalse(dupInit.accepted)
    }

    @Test
    fun lateServiceStopOfADoesNotFinishWaitingB() {
        val c = ConnectRequestCoordinator()
        val first = c.onConnectRequested(ctx(hasEvidence = true)) as ConnectLaunchAction.Proceed
        val ownerA = c.bindNewTransport()
        c.markLaunched(first.requestId)
        c.revokeConnectWork()
        val waitB = c.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.EnqueueWait
        assertFalse(c.onOwnedServiceStopped(ownerA))
        assertEquals(waitB.requestId, c.activeRequestId())
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "b",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = waitB.requestId,
        )
        val early = c.admitEarly(
            callback("b", ordinary = true, sample = RestrictionSample.Open),
        )
        assertTrue(early.completeWait)
        val proceeded = c.continueAfterWait(ctx(ui = ConnState.Probing, probeActive = true), waitB.requestId)
        assertTrue(proceeded is ConnectLaunchAction.Proceed)
    }

    @Test
    fun stopThenConnectAdmitsNeedBypassWhenLiveNetworkKeyMoved() = runBlocking {
        val c = ConnectRequestCoordinator()
        val first = c.onConnectRequested(ctx(hasEvidence = true)) as ConnectLaunchAction.Proceed
        c.markLaunched(first.requestId)
        c.revokeConnectWork()
        val wait = c.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "nb",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        val wifi = NetworkKey(2L, UnderlayKind.Wifi, null, "wifi")
        val admit = c.admitAndApplyFinal(
            ProbeCallback(
                seriesId = "nb",
                sessionEpoch = 0L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = false,
                sample = RestrictionSample.Positive,
                wantsConnected = false,
                uiState = ConnState.Probing,
                liveNetworkKey = wifi,
                liveProfileId = "p",
                userStop = false,
            ),
        ) {}
        assertTrue(admit.accepted)
        assertTrue(admit.completeWait)
        val proceeded = c.continueAfterWait(
            ctx(ui = ConnState.Probing, probeActive = true),
            wait.requestId,
        )
        assertTrue(proceeded is ConnectLaunchAction.Proceed)
    }

    @Test
    fun ownedConnectInitialAdmitsNeedBypassAcrossSessionEpoch() = runBlocking {
        val c = ConnectRequestCoordinator()
        val wait = c.onConnectRequested(ctx()) as ConnectLaunchAction.EnqueueWait
        c.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "epoch",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = wait.requestId,
        )
        val admit = c.admitAndApplyFinal(
            ProbeCallback(
                seriesId = "epoch",
                sessionEpoch = 4L,
                networkEpoch = 2L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = false,
                sample = RestrictionSample.Positive,
                wantsConnected = true,
                uiState = ConnState.Probing,
                liveNetworkKey = cell,
                liveProfileId = "p",
                userStop = false,
            ),
        ) {}
        assertTrue(admit.accepted)
        assertTrue(admit.completeWait)
        assertTrue(admit.applyToReducer)
        c.awaitDecision(wait.requestId)
    }

    @Test
    fun connectingBIgnoresRepeatStopOfA() {
        val c = ConnectRequestCoordinator()
        val first = c.onConnectRequested(ctx(hasEvidence = true)) as ConnectLaunchAction.Proceed
        val ownerA = c.bindNewTransport()
        c.markLaunched(first.requestId)
        assertTrue(c.onOwnedServiceStopped(ownerA))
        c.finishAttempt()
        val second = c.onConnectRequested(ctx(ui = ConnState.Ready, hasEvidence = true))
            as ConnectLaunchAction.Proceed
        c.markLaunched(second.requestId)
        val ownerB = c.bindNewTransport()
        assertFalse(c.onOwnedServiceStopped(ownerA))
        assertEquals(second.requestId, c.activeRequestId())
        assertTrue(c.onOwnedServiceStopped(ownerB))
        c.finishAttempt()
        val third = c.onConnectRequested(ctx(ui = ConnState.Ready, hasEvidence = true))
        assertTrue(third is ConnectLaunchAction.Proceed)
    }

    @Test
    fun sameNetworkAndHashDoNotSubstituteTransportOwner() {
        val c = ConnectRequestCoordinator()
        val a = c.onConnectRequested(ctx(hasEvidence = true)) as ConnectLaunchAction.Proceed
        val ownerA = c.bindNewTransport()
        c.markLaunched(a.requestId)
        c.revokeConnectWork()
        val b = c.onConnectRequested(ctx(ui = ConnState.Ready, hasEvidence = true))
            as ConnectLaunchAction.Proceed
        c.markLaunched(b.requestId)
        c.bindNewTransport()
        assertFalse(c.onOwnedServiceStopped(ownerA))
        assertEquals(b.requestId, c.activeRequestId())
    }
}
