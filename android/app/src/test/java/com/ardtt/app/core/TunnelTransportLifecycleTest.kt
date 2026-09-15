package com.ardtt.app.core

import com.ardtt.app.bypass.CallHashFailure
import com.ardtt.app.bypass.CallHashErrorKind
import com.ardtt.app.bypass.CallHashOutcome
import com.ardtt.app.bypass.CallHashPhase
import com.ardtt.app.bypass.CallRecreateIdentity
import com.ardtt.app.bypass.beginCallRecreateChanged
import com.ardtt.app.bypass.callRecreateChangedFromOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary between ConnectionManager and VpnTunnelService.
 *
 * The host calls the same production session/serializer/dispatch helpers
 * the Android types use. Side effects (backend, TUN, scope, AMS stopSelf)
 * are counted here; the decision is not reimplemented in the test.
 */
class TunnelTransportLifecycleTest {
    private val cell = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell", carrier = "25001")

    private val hash = "LrSXnSsyDo_yNx28kZQp9GBC-8T7xjCAx4D0tJ2paLI"

    private fun connectedBypassSnapshot(): ConnectionSnapshot {
        val evidence = ReachabilityEvidence(
            networkKey = cell,
            profileId = "p",
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Timeout,
            google = CheckOutcome.Timeout,
            ruService = CheckOutcome.Success,
            restriction = RestrictionHint.Confirmed,
            whitelistScorePercent = 80,
        ).withFreshStrongTtl(atElapsedMs = 1L)
        val started = ConnectionReducer.reduce(
            ConnectionSnapshot(
                underlay = UnderlaySnapshot(
                    key = cell,
                    kind = UnderlayKind.Cellular,
                    availability = UnderlayAvailability.Usable,
                    handle = 1L,
                    simId = 11,
                    cellularConnected = true,
                    networkEpoch = 1L,
                ),
                call = CallSessionState(
                    hashPresent = true,
                    validity = CallValidity.Valid,
                    profileId = "p",
                    identityToken = "h",
                    callEpoch = 2L,
                ),
                evidence = evidence,
            ),
            ConnectionEvent.UserConnect(
                mode = ConnPathMode.Bypass,
                profileId = "p",
                hasCallHash = true,
                silentRecreate = true,
                callIdentityToken = "h",
            ),
            1L,
        )
        return ConnectionReducer.reduce(
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

    private fun recreateIdentity(state: ConnectionSnapshot) = CallRecreateIdentity(
        sessionEpoch = state.sessionEpoch,
        generation = if (state.call.createGeneration != 0L) state.call.createGeneration else 4L,
        profileId = state.intent.profileId,
        callEpoch = state.call.callEpoch,
        requestId = 8L,
        wantsConnected = state.intent.wantsConnected,
    )

    private fun ctx(
        ui: ConnState = ConnState.Idle,
        hasEvidence: Boolean = true,
        probeActive: Boolean = false,
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
        probeJobActive = probeActive,
        transportStartingOrLive = transportBusy,
        hasCallHash = true,
    )

    @Test
    fun stopSelfResultLeavesServiceUpWhenNewerStartAlreadyAccepted() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        assertNotNull(host.managerRequestStart())
        host.deliverAll()
        assertTrue(host.backendAlive)

        host.managerStop(uiToReady = true)
        val stopId = host.pending.first().startId
        val acceptedB = host.systemAcceptStartWithoutSerializer(owner = 99L)
        assertTrue(acceptedB > stopId)
        host.deliverNext()

        assertTrue(host.serviceAlive)
        assertEquals(0, host.destroyCount)
        assertTrue(host.scopeAlive)
        assertTrue(host.uiReadyAfterStop)

        host.deliverNext()
        assertTrue(host.backendAlive)
        assertTrue(host.tunAlive)
        assertTrue(host.scopeAlive)
        assertEquals(0, host.destroyCount)
        assertEquals(2, host.startDeliveries)
    }

    @Test
    fun deliveredStartBIgnoresStaleStopOfA() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        val ownerA = host.managerRequestStart()!!
        host.deliverAll()
        host.requests.revokeConnectWork()
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        val ownerB = host.forceStartNow()
        host.deliverAll()
        assertEquals(ownerB, host.session.boundOwner)

        host.systemAcceptStop(ownerA)
        host.deliverAll()

        assertNotEquals(ownerA, ownerB)
        assertEquals(ownerB, host.session.boundOwner)
        assertTrue(host.backendAlive)
        assertTrue(host.tunAlive)
        assertTrue(host.scopeAlive)
        assertEquals(0, host.destroyCount)
        assertEquals(0, host.backendStopCount)
        assertFalse(host.finishedAttempts.contains(ownerB))
    }

    @Test
    fun lateCleanupOfADoesNotTakeOwnerB() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        val ownerA = host.managerRequestStart()!!
        host.deliverAll()
        host.requests.revokeConnectWork()
        val proceedB = host.requests.onConnectRequested(ctx(ui = ConnState.Ready))
            as ConnectLaunchAction.Proceed
        val ownerB = host.forceStartNow()
        host.deliverAll()

        host.injectDestroyReporting(ownerA)
        assertEquals(ownerB, host.session.boundOwner)
        assertTrue(host.backendAlive)
        assertTrue(host.scopeAlive)
        assertFalse(host.finishedAttempts.contains(ownerB))
        assertEquals(2, host.startDeliveries)
        assertEquals(proceedB.requestId, host.requests.activeRequestId())
    }

    @Test
    fun repeatDestroyOfADoesNotFinishB() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        val ownerA = host.managerRequestStart()!!
        host.deliverAll()
        host.managerStop(uiToReady = true)
        val proceedB = host.requests.onConnectRequested(ctx(ui = ConnState.Ready))
            as ConnectLaunchAction.Proceed
        val ownerB = host.forceStartNow()
        host.deliverAll()

        host.injectDestroyReporting(ownerA)
        host.injectDestroyReporting(ownerA)
        assertTrue(host.backendAlive)
        assertFalse(host.finishedAttempts.contains(ownerB))
        assertEquals(proceedB.requestId, host.requests.activeRequestId())
    }

    @Test
    fun connectBWaitsOutStopAThenStartsOnce() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        assertEquals(0, host.pending.count { !it.stop })
        assertEquals(1, host.startDeliveries)

        host.deliverAll()
        assertEquals(2, host.startDeliveries)
        assertTrue(host.backendAlive)
        assertTrue(host.tunAlive)
        assertTrue(host.scopeAlive)
        assertEquals(1, host.destroyCount)
        assertEquals(1, host.startedOwners.groupingBy { it }.eachCount().values.maxOrNull())
    }

    @Test
    fun bWaitingOnInitialProbeKeepsRequestUntilStart() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)

        val waitB = host.requests.onConnectRequested(
            ctx(ui = ConnState.Ready, hasEvidence = false),
        ) as ConnectLaunchAction.EnqueueWait
        host.deliverAll()

        assertEquals(waitB.requestId, host.requests.activeRequestId())
        assertEquals(1, host.startDeliveries)
        assertFalse(host.backendAlive)

        host.requests.registerProbe(
            role = ProbeRole.ConnectInitial,
            seriesId = "b",
            sessionEpoch = 0L,
            networkEpoch = 1L,
            profileId = "p",
            networkKey = cell,
            requestId = waitB.requestId,
        )
        val early = host.requests.admitEarly(
            ProbeCallback(
                seriesId = "b",
                sessionEpoch = 0L,
                networkEpoch = 1L,
                profileId = "p",
                networkKey = cell,
                ordinarySuccess = true,
                sample = RestrictionSample.Open,
                wantsConnected = true,
                uiState = ConnState.Probing,
                liveNetworkKey = cell,
                liveProfileId = "p",
                userStop = false,
            ),
        )
        assertTrue(early.completeWait)
        val proceeded = host.requests.continueAfterWait(
            ctx(ui = ConnState.Probing, probeActive = true),
            waitB.requestId,
        )
        assertTrue(proceeded is ConnectLaunchAction.Proceed)
        val ownerB = host.managerRequestStart()
        assertNotNull(ownerB)
        host.deliverAll()
        assertEquals(2, host.startDeliveries)
        assertTrue(host.backendAlive)
        assertFalse(host.finishedAttempts.contains(ownerB))
    }

    @Test
    fun realStopOfBReleasesServiceAndAllowsConnectC() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        host.deliverAll()
        val ownerB = host.session.boundOwner
        assertTrue(ownerB != 0L)
        assertTrue(host.backendAlive)

        host.managerStop(uiToReady = true)
        host.deliverAll()
        assertFalse(host.serviceAlive)
        assertFalse(host.backendAlive)
        assertFalse(host.tunAlive)
        assertFalse(host.scopeAlive)
        assertTrue(host.finishedAttempts.contains(ownerB))

        host.injectDestroyReporting(ownerB)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        val ownerC = host.managerRequestStart()
        assertNotNull(ownerC)
        host.deliverAll()
        assertEquals(3, host.startDeliveries)
        assertTrue(host.backendAlive)
    }

    @Test
    fun notificationStopWithoutOwnerStopsCurrentBound() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        val owner = host.managerRequestStart()!!
        host.deliverAll()
        host.notificationStop()
        host.deliverAll()
        assertFalse(host.serviceAlive)
        assertFalse(host.backendAlive)
        assertFalse(host.scopeAlive)
        assertTrue(host.finishedAttempts.contains(owner))
    }

    @Test
    fun treatStopAsSoftRestartIgnoresOnlyInternalRestart() {
        assertTrue(treatStopAsSoftRestart(managerSoftRestart = true, acceptedUserStop = false))
        assertFalse(treatStopAsSoftRestart(managerSoftRestart = true, acceptedUserStop = true))
        assertFalse(treatStopAsSoftRestart(managerSoftRestart = false, acceptedUserStop = true))
        assertFalse(treatStopAsSoftRestart(managerSoftRestart = false, acceptedUserStop = false))
    }

    @Test
    fun targetedStopDoesNotRevokeNewerUnboundConnect() {
        assertFalse(
            shouldRevokeConnectOnServiceStop(
                origin = TunnelStopOrigin.TargetedStop,
                applyTeardown = true,
                stoppedOwner = 1L,
                liveTransportOwner = 1L,
                activeRequestTransportOwner = 0L,
                hasActiveConnectRequest = true,
                wantsConnected = true,
                managerSoftRestart = false,
            ),
        )
    }

    @Test
    fun targetedStopDoesNotRevokeNewerBoundConnect() {
        assertFalse(
            shouldRevokeConnectOnServiceStop(
                origin = TunnelStopOrigin.TargetedStop,
                applyTeardown = true,
                stoppedOwner = 1L,
                liveTransportOwner = 2L,
                activeRequestTransportOwner = 2L,
                hasActiveConnectRequest = true,
                wantsConnected = true,
                managerSoftRestart = false,
            ),
        )
    }

    @Test
    fun shadeStopRevokesWaitingConnect() {
        assertTrue(
            shouldRevokeConnectOnServiceStop(
                origin = TunnelStopOrigin.Shade,
                applyTeardown = true,
                stoppedOwner = 1L,
                liveTransportOwner = 1L,
                activeRequestTransportOwner = 0L,
                hasActiveConnectRequest = true,
                wantsConnected = true,
                managerSoftRestart = false,
            ),
        )
        assertTrue(
            shouldRevokeConnectOnServiceStop(
                origin = TunnelStopOrigin.Shade,
                applyTeardown = true,
                stoppedOwner = 1L,
                liveTransportOwner = 1L,
                activeRequestTransportOwner = 0L,
                hasActiveConnectRequest = true,
                wantsConnected = true,
                managerSoftRestart = true,
            ),
        )
    }

    @Test
    fun destroyCallbackDoesNotRevokeConnectIntent() {
        assertFalse(
            shouldRevokeConnectOnServiceStop(
                origin = TunnelStopOrigin.Destroy,
                applyTeardown = true,
                stoppedOwner = 1L,
                liveTransportOwner = 1L,
                activeRequestTransportOwner = 0L,
                hasActiveConnectRequest = true,
                wantsConnected = true,
                managerSoftRestart = false,
            ),
        )
    }

    @Test
    fun targetedStopOfCurrentAttemptStillRevokes() {
        assertTrue(
            shouldRevokeConnectOnServiceStop(
                origin = TunnelStopOrigin.TargetedStop,
                applyTeardown = true,
                stoppedOwner = 1L,
                liveTransportOwner = 1L,
                activeRequestTransportOwner = 1L,
                hasActiveConnectRequest = true,
                wantsConnected = true,
                managerSoftRestart = false,
            ),
        )
    }

    @Test
    fun rejectedTeardownDoesNotRevokeConnect() {
        assertFalse(
            shouldRevokeConnectOnServiceStop(
                origin = TunnelStopOrigin.Shade,
                applyTeardown = false,
                stoppedOwner = 1L,
                liveTransportOwner = 1L,
                activeRequestTransportOwner = 0L,
                hasActiveConnectRequest = true,
                wantsConnected = true,
                managerSoftRestart = true,
            ),
        )
    }

    private fun startAThenStopAndConnectB(
        host: FakeTunnelHost,
        connectCtx: ConnectLaunchContext = ctx(ui = ConnState.Ready),
    ): ConnectLaunchAction {
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        val action = host.requests.onConnectRequested(connectCtx)
        if (action !is ConnectLaunchAction.Ignore) {
            host.applyLiveConnectIntent()
        }
        return action
    }

    @Test
    fun notificationStopDuringSilentRecreateRevokesIntentBeforeLateGenerator() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        val owner = host.managerRequestStart()!!
        host.deliverAll()
        val startsBeforeStop = host.startDeliveries

        var state = connectedBypassSnapshot()
        state = ConnectionReducer.reduce(
            state,
            beginCallRecreateChanged(
                identity = recreateIdentity(state),
                holdService = true,
                underlayAllowsOps = true,
            ),
            20L,
        ).state
        val captured = recreateIdentity(state)
        host.snapshot = state
        host.capturedRecreate = captured
        host.managerSoftRestart = true
        host.generation = captured.generation
        host.sessionEpoch = state.sessionEpoch
        host.wantsConnected = true

        host.notificationStop()
        host.deliverAll()

        assertFalse(host.serviceAlive)
        assertTrue(host.finishedAttempts.contains(owner))
        assertTrue(host.recreateCancelled)
        assertTrue(host.timerCleared)
        assertFalse(host.snapshot.intent.wantsConnected)
        assertFalse(host.wantsConnected)
        assertEquals(1, host.destroyCount)

        val tokenBefore = host.snapshot.call.identityToken
        host.lateRecreateOutcome(CallHashOutcome.Success(hash))
        host.lateRecreateOutcome(
            CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.TransientNetwork,
                    phase = CallHashPhase.OAuth,
                    message = "timeout",
                ),
            ),
        )
        host.fireRecoveryTimer()
        host.injectDestroy()

        assertTrue(host.savedHashes.isEmpty())
        assertEquals(0, host.reconnectIssued)
        assertEquals(startsBeforeStop, host.startDeliveries)
        assertFalse(host.snapshot.intent.wantsConnected)
        assertEquals(tokenBefore, host.snapshot.call.identityToken)
        assertEquals(
            RecoveryCommand.None,
            ConnectionReducer.reduce(
                host.snapshot,
                ConnectionEvent.Clock(120_000L),
                120_000L,
            ).command,
        )
        assertNull(host.requests.activeRequestId())
    }

    @Test
    fun targetedStopAAfterConnectBStartsBOnce() {
        val host = FakeTunnelHost()
        host.autoFlushPosted = false
        startAThenStopAndConnectB(host)
        assertNull(host.managerRequestStart())
        val requestB = host.requests.activeRequestId()
        assertNotNull(requestB)
        assertTrue(host.snapshot.intent.wantsConnected)
        host.deliverAll()
        assertEquals(requestB, host.requests.activeRequestId())
        assertTrue(host.snapshot.intent.wantsConnected)
        assertFalse(host.recreateCancelled)
        host.flushPosted()
        assertEquals(2, host.startDeliveries)
        host.flushPosted()
        assertEquals(2, host.startDeliveries)
        assertEquals(requestB, host.requests.activeRequestId())
        assertTrue(host.backendAlive)
        assertTrue(host.snapshot.intent.wantsConnected)
    }

    @Test
    fun targetedStopAWhileBWaitsForProbeDoesNotCancelB() {
        val host = FakeTunnelHost()
        val action = startAThenStopAndConnectB(host, ctx(ui = ConnState.Ready, hasEvidence = false))
        assertTrue(action is ConnectLaunchAction.EnqueueWait)
        val requestB = host.requests.activeRequestId()
        assertNotNull(requestB)
        host.deliverAll()
        assertEquals(requestB, host.requests.activeRequestId())
        assertTrue(host.snapshot.intent.wantsConnected)
        assertTrue(host.wantsConnected)
        assertFalse(host.recreateCancelled)
        assertEquals(1, host.startDeliveries)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun shadeStopWhileBWaitingCancelsBAndDoesNotStartLater() {
        val host = FakeTunnelHost()
        host.autoFlushPosted = false
        startAThenStopAndConnectB(host)
        assertNull(host.managerRequestStart())
        assertNotNull(host.requests.activeRequestId())
        host.notificationStop()
        host.deliverAll()
        host.flushPosted()
        assertNull(host.requests.activeRequestId())
        assertFalse(host.snapshot.intent.wantsConnected)
        assertEquals(1, host.startDeliveries)
        assertTrue(host.recreateCancelled)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun duplicateStopAndDestroyCallbacksDoNotChangeNextAttempt() {
        val host = FakeTunnelHost()
        host.autoFlushPosted = false
        startAThenStopAndConnectB(host)
        assertNull(host.managerRequestStart())
        val requestB = host.requests.activeRequestId()
        assertNotNull(requestB)
        val ownerA = host.session.boundOwner
        host.deliverStopCommandOnly()
        assertEquals(requestB, host.requests.activeRequestId())
        assertTrue(host.snapshot.intent.wantsConnected)
        host.replayStopped(
            owner = ownerA,
            origin = TunnelStopOrigin.TargetedStop,
            applyTeardown = true,
            releaseStartGate = false,
        )
        assertEquals(requestB, host.requests.activeRequestId())
        host.finishDestroyIfScheduled()
        host.injectDestroy()
        host.flushPosted()
        assertEquals(2, host.startDeliveries)
        assertEquals(requestB, host.requests.activeRequestId())
        assertTrue(host.snapshot.intent.wantsConnected)
        host.injectDestroy()
        host.replayStopped(
            owner = ownerA,
            origin = TunnelStopOrigin.TargetedStop,
            applyTeardown = true,
            releaseStartGate = true,
        )
        assertEquals(requestB, host.requests.activeRequestId())
        assertEquals(2, host.startDeliveries)
        assertTrue(host.snapshot.intent.wantsConnected)
    }

    @Test
    fun stopWithoutNextConnectDestroysService() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        assertTrue(host.uiReadyAfterStop)
        host.deliverAll()
        assertFalse(host.serviceAlive)
        assertFalse(host.backendAlive)
        assertFalse(host.tunAlive)
        assertFalse(host.scopeAlive)
        assertEquals(1, host.destroyCount)
        assertEquals(1, host.startDeliveries)
    }

    @Test
    fun softRestartDoesNotFinishAttempt() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        val owner = host.managerRequestStart()!!
        host.deliverAll()
        val dispatched = dispatchServiceStopped(
            requests = host.requests,
            serializer = host.serializer,
            owner = owner,
            releaseStartGate = false,
            softRestart = true,
        )
        assertFalse(dispatched.finishLiveAttempt)
        assertNull(dispatched.deferredStart)
        assertTrue(host.backendAlive)
        assertEquals(owner, host.session.boundOwner)
        assertEquals(0, host.finishedAttempts.size)
    }

    @Test
    fun commandStopDoesNotFlushQueuedStartBeforeDestroy() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        host.deliverStopCommandOnly()
        assertEquals(1, host.startDeliveries)
        assertTrue(host.scopeAlive)
        host.finishDestroyIfScheduled()
        host.deliverAll()
        assertEquals(2, host.startDeliveries)
        assertTrue(host.backendAlive)
    }

    @Test
    fun userStopWhileBDeferredDoesNotStartB() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        host.managerStop(uiToReady = true)
        host.deliverAll()
        assertEquals(1, host.startDeliveries)
        assertFalse(host.backendAlive)
        assertFalse(host.serviceAlive)
    }

    @Test
    fun postedStartBAfterStopBDoesNotStart() {
        val host = FakeTunnelHost()
        host.autoFlushPosted = false
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        host.deliverAll()
        assertEquals(1, host.posted.size)
        assertEquals(1, host.startDeliveries)

        host.managerStop(uiToReady = true)
        host.flushPosted()
        assertEquals(1, host.startDeliveries)
        assertNull(host.sessionHolderPath)
        assertEquals(1, host.rejectedStarts.size)
    }

    @Test
    fun postedStartBAfterAttemptFailedDoesNotStart() {
        val host = FakeTunnelHost()
        host.autoFlushPosted = false
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        host.deliverAll()
        host.requests.finishAttempt()
        host.serializer.revokePending()
        host.generation++
        host.wantsConnected = false
        host.flushPosted()
        assertEquals(1, host.startDeliveries)
        assertTrue(host.rejectedStarts.contains("revoked") || host.rejectedStarts.contains("wantsConnected"))
    }

    @Test
    fun postedStartBDoesNotTakeConnectCOwner() {
        val host = FakeTunnelHost()
        host.autoFlushPosted = false
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        host.deliverAll()
        assertEquals(1, host.posted.size)

        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        val ownerC = host.managerRequestStart()
        assertNotNull(ownerC)
        host.deliverAll()
        val deliveriesBeforeFlush = host.startDeliveries
        host.flushPosted()
        assertEquals(deliveriesBeforeFlush, host.startDeliveries)
        assertEquals(ownerC, host.session.boundOwner)
        assertFalse(host.finishedAttempts.contains(ownerC))
    }

    @Test
    fun revokeBeforeStopTunnelDropsQueuedStart() {
        val host = FakeTunnelHost()
        host.autoFlushPosted = false
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        host.serializer.revokePending()
        host.generation++
        host.wantsConnected = false
        host.deliverAll()
        host.flushPosted()
        assertEquals(1, host.startDeliveries)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun legitimatePostedStartBRunsOnce() {
        val host = FakeTunnelHost()
        host.autoFlushPosted = false
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        host.deliverAll()
        assertEquals(1, host.startDeliveries)
        host.flushPosted()
        assertEquals(2, host.startDeliveries)
        host.flushPosted()
        assertEquals(2, host.startDeliveries)
        assertTrue(host.backendAlive)
    }

    @Test
    fun deferredLeaseRejectsAfterRevokeEvenIfWantsConnected() {
        val serializer = TunnelStartSerializer()
        val lease = serializer.nextLease(
            requestId = 1L,
            sessionEpoch = 2L,
            generation = 3L,
            path = VpnPath.Direct,
        )
        serializer.revokePending()
        val live = LiveDeferredStart(
            requestId = 1L,
            sessionEpoch = 2L,
            generation = 3L,
            wantsConnected = true,
        )
        assertEquals("revoked", deferredStartRejectReason(lease, live, serializer.gateEpoch))
        val current = serializer.nextLease(1L, 2L, 3L, VpnPath.Direct)
        assertNull(deferredStartRejectReason(current, live, serializer.gateEpoch))
        val stopped = live.copy(wantsConnected = false, generation = 4L, requestId = 9L)
        assertEquals("wantsConnected", deferredStartRejectReason(current, stopped, serializer.gateEpoch))
        val otherRequest = live.copy(requestId = 9L)
        assertEquals("request", deferredStartRejectReason(current, otherRequest, serializer.gateEpoch))
    }

    @Test
    fun failedPostedStartDoesNotFailConnectC() {
        val host = FakeTunnelHost()
        host.autoFlushPosted = false
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        assertNull(host.managerRequestStart())
        host.deliverAll()
        host.managerStop(uiToReady = true)
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        val ownerC = host.managerRequestStart()
        assertNotNull(ownerC)
        host.deliverAll()
        host.failNextStart = true
        host.flushPosted()
        assertFalse(host.finishedAttempts.contains(ownerC))
        assertEquals(ownerC, host.session.boundOwner)
    }

    @Test
    fun revokeAfterRefreshUsesLastCommandStartId() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        assertEquals(1, host.session.boundStartId)
        host.systemAcceptAuxiliary()
        host.deliverAll()
        assertEquals(1, host.session.boundStartId)
        assertEquals(2, host.session.lastCommandStartId)
        host.systemRevoke()
        assertFalse(host.serviceAlive)
        assertEquals(1, host.destroyCount)
        assertEquals(true, host.lastRevokeStopSelfResult)
        assertEquals(0, host.stopSelfFallbackCount)
    }

    @Test
    fun revokeAfterSessionControlAndRestartUsesLastStartId() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.systemAcceptAuxiliary()
        host.deliverAll()
        host.systemAcceptAuxiliary()
        host.deliverAll()
        assertEquals(1, host.session.boundStartId)
        assertEquals(3, host.session.lastCommandStartId)
        host.systemRevoke()
        assertFalse(host.serviceAlive)
        assertEquals(1, host.destroyCount)
        assertEquals(true, host.lastRevokeStopSelfResult)
    }

    @Test
    fun revokeFallsBackToStopSelfWhenStartIdIsStale() {
        val session = TunnelServiceSession()
        session.onStart(owner = 5L, startId = 1)
        session.noteCommand(2)
        val decision = decideVpnRevoke(
            capturedOwner = 5L,
            liveOwner = 5L,
            lastCommandStartId = 1,
        )
        assertTrue(decision.applyTeardown)
        assertTrue(decision.fallbackStopSelf)
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.systemAcceptAuxiliary()
        host.deliverAll()
        host.lastAcceptedStartId = 99
        host.systemRevoke()
        assertEquals(false, host.lastRevokeStopSelfResult)
        assertEquals(1, host.stopSelfFallbackCount)
        assertFalse(host.serviceAlive)
        assertEquals(1, host.destroyCount)
    }

    @Test
    fun backgroundRevokeDoesNotKillNewerConnect() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        val ownerA = host.managerRequestStart()!!
        host.deliverAll()
        host.systemRevoke(deferToMain = true)
        host.requests.revokeConnectWork()
        host.requests.onConnectRequested(ctx(ui = ConnState.Ready)) as ConnectLaunchAction.Proceed
        val ownerB = host.forceStartNow()
        host.deliverAll()
        host.flushMain()
        assertEquals(1, host.revokeIgnored)
        assertEquals(0, host.revokeTeardowns)
        assertEquals(ownerB, host.session.boundOwner)
        assertNotEquals(ownerA, ownerB)
        assertTrue(host.backendAlive)
        assertEquals(0, host.destroyCount)
    }

    @Test
    fun repeatRevokeAndDestroyAreIdempotent() {
        val host = FakeTunnelHost()
        host.requests.onConnectRequested(ctx()) as ConnectLaunchAction.Proceed
        host.managerRequestStart()
        host.deliverAll()
        host.systemRevoke()
        assertEquals(1, host.destroyCount)
        host.systemRevoke()
        host.injectDestroy()
        assertFalse(host.backendAlive)
        assertFalse(host.serviceAlive)
    }
}

/**
 * Stands in for AMS + one VpnTunnelService instance.
 * Decisions come from [TunnelServiceSession] / [TunnelStartSerializer] /
 * [dispatchServiceStopped]; this type only applies those decisions.
 */
internal class FakeTunnelHost {
    val session = TunnelServiceSession()
    val serializer = TunnelStartSerializer()
    val requests = ConnectRequestCoordinator()

    private var nextStartId = 1
    var lastAcceptedStartId = 0
    val pending = ArrayDeque<FakeServiceCommand>()
    val posted = ArrayDeque<DeferredTunnelStart>()
    var autoFlushPosted = true
    var failNextStart = false
    var managerSoftRestart = false
    var snapshot: ConnectionSnapshot = ConnectionSnapshot()
    var capturedRecreate: CallRecreateIdentity? = null
    var recreateCancelled = false
    var timerCleared = false
    val savedHashes = mutableListOf<String>()
    var reconnectIssued = 0
    var sessionHolderPath: VpnPath? = null
    var generation = 0L
    var sessionEpoch = 0L
    var wantsConnected = true
    var serviceAlive = false
    var backendAlive = false
    var tunAlive = false
    var scopeAlive = true
    var startDeliveries = 0
    var backendStopCount = 0
    var destroyCount = 0
    var uiReadyAfterStop = false
    var destroyScheduled = false
    val finishedAttempts = mutableListOf<Long>()
    val startedOwners = mutableListOf<Long>()
    val rejectedStarts = mutableListOf<String>()
    var stopSelfFallbackCount = 0
    var revokeTeardowns = 0
    var revokeIgnored = 0
    var lastRevokeStopSelfResult: Boolean? = null
    private var deferredRevoke: (() -> Unit)? = null

    fun liveCheck() = LiveDeferredStart(
        requestId = requests.activeRequestId(),
        sessionEpoch = sessionEpoch,
        generation = generation,
        wantsConnected = wantsConnected,
    )

    fun managerRequestStart(): Long? {
        applyLiveConnectIntent()
        sessionEpoch++
        val lease = serializer.nextLease(
            requestId = requests.activeRequestId(),
            sessionEpoch = sessionEpoch,
            generation = generation,
            path = VpnPath.Direct,
        )
        if (!serializer.admitStart(lease)) return null
        return executeLease(lease)
    }

    fun executeLease(lease: DeferredTunnelStart): Long? {
        val reason = deferredStartRejectReason(lease, liveCheck(), serializer.gateEpoch)
        if (reason != null) {
            rejectedStarts += reason
            return null
        }
        if (failNextStart) {
            failNextStart = false
            throw IllegalStateException("startForegroundService failed")
        }
        sessionHolderPath = lease.path
        val owner = requests.bindNewTransport()
        serializer.noteStartIssued(owner)
        systemAcceptStart(owner)
        return owner
    }

    fun flushPosted() {
        while (posted.isNotEmpty()) {
            val lease = posted.removeFirst()
            runCatching { executeLease(lease) }.onFailure { error ->
                val reason = deferredStartRejectReason(lease, liveCheck(), serializer.gateEpoch)
                if (reason != null) {
                    rejectedStarts += reason
                } else {
                    throw error
                }
            }
        }
        while (pending.isNotEmpty()) deliverNext()
        finishDestroyIfScheduled()
    }

    fun forceStartNow(): Long {
        val owner = requests.bindNewTransport()
        serializer.noteStartIssued(owner)
        systemAcceptStart(owner)
        return owner
    }

    fun applyLiveConnectIntent() {
        wantsConnected = true
        snapshot = snapshot.copy(intent = snapshot.intent.copy(wantsConnected = true))
    }

    fun managerStop(uiToReady: Boolean) {
        requests.revokeConnectWork()
        serializer.revokePending()
        generation++
        snapshot = ConnectionReducer.reduce(snapshot, ConnectionEvent.UserDisconnect, 50L).state
        wantsConnected = snapshot.intent.wantsConnected
        sessionHolderPath = null
        val owner = serializer.beginStop()
        systemAcceptStop(owner)
        if (uiToReady) uiReadyAfterStop = true
    }

    fun notificationStop() {
        systemAcceptStop(requestedOwner = 0L)
    }

    fun systemAcceptStart(owner: Long): Int {
        val id = nextStartId++
        lastAcceptedStartId = id
        pending.addLast(FakeServiceCommand(stop = false, owner = owner, startId = id))
        serviceAlive = true
        return id
    }

    fun systemAcceptStartWithoutSerializer(owner: Long): Int = systemAcceptStart(owner)

    fun systemAcceptAuxiliary(): Int {
        val id = nextStartId++
        lastAcceptedStartId = id
        pending.addLast(FakeServiceCommand(stop = false, owner = 0L, startId = id, auxiliary = true))
        serviceAlive = true
        return id
    }

    fun systemRevoke(deferToMain: Boolean = false) {
        val capturedOwner = session.boundOwner
        val capturedStartId = session.lastCommandStartId
        val work = {
            applyRevoke(capturedOwner, capturedStartId)
        }
        if (deferToMain) {
            deferredRevoke = work
        } else {
            work()
        }
    }

    fun flushMain() {
        deferredRevoke?.invoke()
        deferredRevoke = null
        finishDestroyIfScheduled()
    }

    private fun applyRevoke(capturedOwner: Long, capturedStartId: Int) {
        val decision = decideVpnRevoke(capturedOwner, session.boundOwner, capturedStartId)
        if (!decision.applyTeardown) {
            revokeIgnored++
            return
        }
        revokeTeardowns++
        session.markRevoked(decision.reportOwner)
        backendAlive = false
        tunAlive = false
        val gone = if (decision.stopSelfStartId > 0) {
            amsStopSelfResult(decision.stopSelfStartId)
        } else {
            false
        }
        lastRevokeStopSelfResult = gone
        if (!gone && decision.fallbackStopSelf) {
            stopSelfFallbackCount++
        }
        if (gone || decision.fallbackStopSelf) {
            destroyScheduled = true
            destroyScheduled = false
            destroyFromAms()
        }
    }

    fun injectDestroy() {
        destroyFromAms()
    }

    fun systemAcceptStop(requestedOwner: Long): Int {
        val id = nextStartId++
        lastAcceptedStartId = id
        pending.addLast(FakeServiceCommand(stop = true, owner = requestedOwner, startId = id))
        serviceAlive = true
        return id
    }

    fun deliverAll() {
        do {
            while (pending.isNotEmpty()) deliverNext()
            finishDestroyIfScheduled()
            if (autoFlushPosted) flushPosted()
        } while (pending.isNotEmpty() || (autoFlushPosted && posted.isNotEmpty()))
    }

    fun deliverNext() {
        val cmd = pending.removeFirst()
        when {
            cmd.auxiliary -> session.noteCommand(cmd.startId)
            cmd.stop -> deliverStop(cmd)
            else -> {
                session.onStart(cmd.owner, cmd.startId)
                backendAlive = true
                tunAlive = true
                scopeAlive = true
                startDeliveries++
                startedOwners += cmd.owner
            }
        }
    }

    fun deliverStopCommandOnly() {
        val cmd = pending.removeFirst()
        check(cmd.stop)
        deliverStop(cmd, runScheduledDestroy = false)
    }

    fun finishDestroyIfScheduled() {
        if (destroyScheduled) {
            destroyScheduled = false
            destroyFromAms()
        }
    }

    fun injectDestroyReporting(owner: Long) {
        val dispatched = dispatchServiceStopped(
            requests = requests,
            serializer = serializer,
            owner = owner,
            releaseStartGate = true,
            softRestart = false,
        )
        recordFinish(owner, dispatched)
        dispatched.deferredStart?.let { posted.addLast(it) }
        if (autoFlushPosted) flushPosted()
    }

    fun replayStopped(
        owner: Long,
        origin: TunnelStopOrigin,
        applyTeardown: Boolean,
        releaseStartGate: Boolean,
    ) {
        dispatchManagerServiceStopped(
            owner = owner,
            releaseStartGate = releaseStartGate,
            origin = origin,
            applyTeardown = applyTeardown,
        )
    }

    private fun deliverStop(cmd: FakeServiceCommand, runScheduledDestroy: Boolean = true) {
        val decision = session.onStop(cmd.owner, cmd.startId)
        val origin = if (cmd.owner == 0L) {
            TunnelStopOrigin.Shade
        } else {
            TunnelStopOrigin.TargetedStop
        }
        if (decision.applyTeardown) {
            backendAlive = false
            tunAlive = false
            backendStopCount++
        }
        val dispatched = dispatchManagerServiceStopped(
            owner = decision.reportOwner,
            releaseStartGate = false,
            origin = origin,
            applyTeardown = decision.applyTeardown,
        )
        assertNull(dispatched.deferredStart)
        val stopId = decision.stopSelfStartId
        if (stopId != null && amsStopSelfResult(stopId)) {
            destroyScheduled = true
            if (runScheduledDestroy) {
                destroyScheduled = false
                destroyFromAms()
            }
        } else if (stopId != null) {
            dispatchManagerServiceStopped(
                owner = decision.reportOwner,
                releaseStartGate = true,
                origin = origin,
                applyTeardown = decision.applyTeardown,
            )
        }
    }

    private fun amsStopSelfResult(startId: Int): Boolean {
        return startId == lastAcceptedStartId
    }

    private fun destroyFromAms() {
        serviceAlive = false
        backendAlive = false
        tunAlive = false
        scopeAlive = false
        destroyCount++
        val report = session.ownerForDestroy()
        dispatchManagerServiceStopped(
            owner = report,
            releaseStartGate = true,
            origin = TunnelStopOrigin.Destroy,
            applyTeardown = true,
        )
    }

    fun lateRecreateOutcome(outcome: CallHashOutcome) {
        val captured = capturedRecreate ?: return
        val live = CallRecreateIdentity(
            sessionEpoch = snapshot.sessionEpoch,
            generation = generation,
            profileId = snapshot.intent.profileId ?: captured.profileId,
            callEpoch = snapshot.call.callEpoch,
            requestId = requests.activeRequestId(),
            wantsConnected = snapshot.intent.wantsConnected,
        )
        val event = callRecreateChangedFromOutcome(
            captured = captured,
            live = live,
            outcome = outcome,
            holdService = true,
            networkAttempts = snapshot.call.createNetworkAttempts,
            underlayAllowsOps = snapshot.underlay.allowsNetworkOps,
        )
        if (event == null) {
            return
        }
        val hash = (outcome as? CallHashOutcome.Success)?.hash
        if (hash != null && event.op == CallCreateOp.Applied) {
            savedHashes += hash
            reconnectIssued++
        }
        snapshot = ConnectionReducer.reduce(snapshot, event, 80L).state
    }

    fun fireRecoveryTimer() {
        val due = snapshot.recovery.nextRetryAtElapsedMs ?: 90_000L
        snapshot = ConnectionReducer.reduce(snapshot, ConnectionEvent.Clock(due), due).state
    }

    private fun dispatchManagerServiceStopped(
        owner: Long,
        releaseStartGate: Boolean,
        origin: TunnelStopOrigin,
        applyTeardown: Boolean,
    ): ServiceStoppedDispatch {
        val acceptedUserStop = acceptedUserStopFromOrigin(origin, applyTeardown)
        val ignore = treatStopAsSoftRestart(managerSoftRestart, acceptedUserStop)
        if (
            shouldRevokeConnectOnServiceStop(
                origin = origin,
                applyTeardown = applyTeardown,
                stoppedOwner = owner,
                liveTransportOwner = requests.liveTransportOwnerId(),
                activeRequestTransportOwner = requests.activeRequestTransportOwner(),
                hasActiveConnectRequest = requests.hasActiveConnectRequest(),
                wantsConnected = snapshot.intent.wantsConnected,
                managerSoftRestart = managerSoftRestart,
            )
        ) {
            recreateCancelled = true
            timerCleared = true
            requests.revokeConnectWork()
            serializer.revokePending()
            snapshot = ConnectionReducer.reduce(snapshot, ConnectionEvent.UserDisconnect, 50L).state
            wantsConnected = snapshot.intent.wantsConnected
            managerSoftRestart = false
            generation++
        }
        val dispatched = dispatchServiceStopped(
            requests = requests,
            serializer = serializer,
            owner = owner,
            releaseStartGate = releaseStartGate,
            softRestart = ignore,
        )
        recordFinish(owner, dispatched)
        dispatched.deferredStart?.let { posted.addLast(it) }
        return dispatched
    }

    private fun recordFinish(owner: Long, dispatched: ServiceStoppedDispatch) {
        if (dispatched.finishLiveAttempt) finishedAttempts += owner
    }
}

internal data class FakeServiceCommand(
    val stop: Boolean,
    val owner: Long,
    val startId: Int,
    val auxiliary: Boolean = false,
)
