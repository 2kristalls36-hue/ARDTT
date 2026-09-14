package com.ardtt.app.core

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
        wantsConnected = true
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

    fun managerStop(uiToReady: Boolean) {
        requests.revokeConnectWork()
        serializer.revokePending()
        generation++
        wantsConnected = false
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

    private fun deliverStop(cmd: FakeServiceCommand, runScheduledDestroy: Boolean = true) {
        val decision = session.onStop(cmd.owner, cmd.startId)
        if (decision.applyTeardown) {
            backendAlive = false
            tunAlive = false
            backendStopCount++
        }
        val dispatched = dispatchServiceStopped(
            requests = requests,
            serializer = serializer,
            owner = decision.reportOwner,
            releaseStartGate = false,
            softRestart = false,
        )
        recordFinish(decision.reportOwner, dispatched)
        assertNull(dispatched.deferredStart)
        val stopId = decision.stopSelfStartId
        if (stopId != null && amsStopSelfResult(stopId)) {
            destroyScheduled = true
            if (runScheduledDestroy) {
                destroyScheduled = false
                destroyFromAms()
            }
        } else if (stopId != null && decision.applyTeardown) {
            val flush = dispatchServiceStopped(
                requests = requests,
                serializer = serializer,
                owner = decision.reportOwner,
                releaseStartGate = true,
                softRestart = false,
            )
            recordFinish(decision.reportOwner, flush)
            flush.deferredStart?.let { posted.addLast(it) }
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
        val dispatched = dispatchServiceStopped(
            requests = requests,
            serializer = serializer,
            owner = report,
            releaseStartGate = true,
            softRestart = false,
        )
        recordFinish(report, dispatched)
        dispatched.deferredStart?.let { posted.addLast(it) }
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
