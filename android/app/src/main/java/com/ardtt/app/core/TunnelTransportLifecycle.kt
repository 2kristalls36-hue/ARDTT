package com.ardtt.app.core

/**
 * Per-Service-instance transport identity.
 *
 * [boundOwner] is the Connect attempt that last issued ACTION_START, not the
 * Android Service object and not [boundStartId]. A late STOP must not tear
 * down a newer attempt that already owns this instance.
 */
internal class TunnelServiceSession {
    @Volatile var boundOwner: Long = 0L
        private set
    @Volatile var boundStartId: Int = 0
        private set
    @Volatile var lastCommandStartId: Int = 0
        private set
    @Volatile var destroyingOwner: Long = 0L
        private set

    fun noteCommand(startId: Int) {
        if (startId > 0) lastCommandStartId = startId
    }

    fun onStart(owner: Long, startId: Int) {
        noteCommand(startId)
        if (owner != 0L) boundOwner = owner
        boundStartId = startId
        destroyingOwner = 0L
    }

    fun onStop(requestedOwner: Long, commandStartId: Int): TunnelStopDecision {
        noteCommand(commandStartId)
        val stale = requestedOwner != 0L &&
            boundOwner != 0L &&
            requestedOwner != boundOwner
        if (stale) {
            return TunnelStopDecision(
                applyTeardown = false,
                stopSelfStartId = null,
                reportOwner = requestedOwner,
            )
        }
        val teardown = if (requestedOwner != 0L) requestedOwner else boundOwner
        destroyingOwner = teardown
        return TunnelStopDecision(
            applyTeardown = true,
            stopSelfStartId = commandStartId,
            reportOwner = teardown,
        )
    }

    fun ownerForDestroy(): Long =
        if (destroyingOwner != 0L) destroyingOwner else boundOwner

    fun markRevoked(owner: Long) {
        destroyingOwner = if (owner != 0L) owner else boundOwner
    }
}

internal data class TunnelStopDecision(
    val applyTeardown: Boolean,
    val stopSelfStartId: Int?,
    val reportOwner: Long,
)

internal data class TunnelRevokeDecision(
    val applyTeardown: Boolean,
    val stopSelfStartId: Int,
    val fallbackStopSelf: Boolean,
    val reportOwner: Long,
)

internal fun decideVpnRevoke(
    capturedOwner: Long,
    liveOwner: Long,
    lastCommandStartId: Int,
): TunnelRevokeDecision {
    val stale = liveOwner != 0L && capturedOwner != liveOwner
    if (stale) {
        return TunnelRevokeDecision(
            applyTeardown = false,
            stopSelfStartId = lastCommandStartId,
            fallbackStopSelf = false,
            reportOwner = capturedOwner,
        )
    }
    return TunnelRevokeDecision(
        applyTeardown = true,
        stopSelfStartId = lastCommandStartId,
        fallbackStopSelf = true,
        reportOwner = if (capturedOwner != 0L) capturedOwner else liveOwner,
    )
}

internal data class DeferredTunnelStart(
    val ticket: Long,
    val gateEpoch: Long,
    val requestId: Long?,
    val sessionEpoch: Long,
    val generation: Long,
    val path: VpnPath,
)

internal data class LiveDeferredStart(
    val requestId: Long?,
    val sessionEpoch: Long,
    val generation: Long,
    val wantsConnected: Boolean,
)

/**
 * Holds the next START until the STOP that is already in flight has
 * destroyed the service. Sending START B before that lets ACTION_STOP
 * of A run stopSelf against an instance Android may already reuse for B.
 *
 * A queued or already posted lease is still rejected after UserDisconnect
 * / AttemptFailed because [revokePending] bumps [gateEpoch].
 */
internal class TunnelStartSerializer {
    @Volatile var lastBoundOwner: Long = 0L
        private set
    @Volatile var stoppingOwner: Long? = null
        private set
    @Volatile var gateEpoch: Long = 0L
        private set

    private var nextTicket = 1L
    private var queuedStart: DeferredTunnelStart? = null

    fun nextLease(
        requestId: Long?,
        sessionEpoch: Long,
        generation: Long,
        path: VpnPath,
    ): DeferredTunnelStart = DeferredTunnelStart(
        ticket = nextTicket++,
        gateEpoch = gateEpoch,
        requestId = requestId,
        sessionEpoch = sessionEpoch,
        generation = generation,
        path = path,
    )

    fun noteStartIssued(owner: Long) {
        lastBoundOwner = owner
        stoppingOwner = null
        queuedStart = null
    }

    fun beginStop(): Long {
        val owner = lastBoundOwner
        if (owner != 0L) stoppingOwner = owner
        return owner
    }

    fun revokePending() {
        queuedStart = null
        gateEpoch++
    }

    fun admitStart(lease: DeferredTunnelStart): Boolean {
        if (stoppingOwner != null) {
            queuedStart = lease
            return false
        }
        return true
    }

    fun onDestroyed(owner: Long): DeferredTunnelStart? {
        val waiting = stoppingOwner ?: return null
        if (owner != 0L && owner != waiting) return null
        stoppingOwner = null
        lastBoundOwner = 0L
        val start = queuedStart
        queuedStart = null
        return start
    }
}

internal fun deferredStartRejectReason(
    lease: DeferredTunnelStart,
    live: LiveDeferredStart,
    serializerGateEpoch: Long,
): String? {
    if (lease.gateEpoch != serializerGateEpoch) return "revoked"
    if (!live.wantsConnected) return "wantsConnected"
    if (lease.generation != live.generation) return "generation"
    if (lease.sessionEpoch != live.sessionEpoch) return "session"
    if (lease.requestId != null && live.requestId != lease.requestId) return "request"
    return null
}

internal data class ServiceStoppedDispatch(
    val finishLiveAttempt: Boolean,
    val deferredStart: DeferredTunnelStart?,
)

internal fun dispatchServiceStopped(
    requests: ConnectRequestCoordinator,
    serializer: TunnelStartSerializer,
    owner: Long,
    releaseStartGate: Boolean,
    softRestart: Boolean,
): ServiceStoppedDispatch {
    if (softRestart) {
        return ServiceStoppedDispatch(
            finishLiveAttempt = false,
            deferredStart = if (releaseStartGate) serializer.onDestroyed(owner) else null,
        )
    }
    val finishLiveAttempt = requests.onOwnedServiceStopped(owner)
    val deferredStart = if (releaseStartGate) serializer.onDestroyed(owner) else null
    return ServiceStoppedDispatch(
        finishLiveAttempt = finishLiveAttempt,
        deferredStart = deferredStart,
    )
}
