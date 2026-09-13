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
        val finalAdmit = c.admitFinal(
            callback("u1", ordinary = false, sample = RestrictionSample.Ignore, userStop = true),
        )
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
        assertTrue(c.lateCallbackBlocked())
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
        val own = c.admitFinal(
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
        )
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
        c2.admitFinal(callback("s2", ordinary = false, sample = RestrictionSample.Ignore))
        assertEquals(
            ConnectLaunchAction.Ignore,
            c2.continueAfterWait(
                ctx(profileId = "q", ui = ConnState.Probing, probeActive = true),
                wait2.requestId,
            ),
        )
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
        assertTrue(c.lateCallbackBlocked())
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
}
