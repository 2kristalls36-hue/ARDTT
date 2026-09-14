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
    @Volatile var destroyingOwner: Long = 0L
        private set

    fun onStart(owner: Long, startId: Int) {
        if (owner != 0L) boundOwner = owner
        boundStartId = startId
        destroyingOwner = 0L
    }

    fun onStop(requestedOwner: Long, commandStartId: Int): TunnelStopDecision {
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
}

internal data class TunnelStopDecision(
    val applyTeardown: Boolean,
    val stopSelfStartId: Int?,
    val reportOwner: Long,
)

/**
 * Holds the next START until the STOP that is already in flight has
 * destroyed the service. Sending START B before that lets ACTION_STOP
 * of A run stopSelf against an instance Android may already reuse for B.
 */
internal class TunnelStartSerializer {
    @Volatile var lastBoundOwner: Long = 0L
        private set
    @Volatile var stoppingOwner: Long? = null
        private set

    private var queuedStart: (() -> Unit)? = null

    fun noteStartIssued(owner: Long) {
        lastBoundOwner = owner
        stoppingOwner = null
        queuedStart = null
    }

    fun beginStop(): Long {
        queuedStart = null
        val owner = lastBoundOwner
        if (owner != 0L) stoppingOwner = owner
        return owner
    }

    fun admitStart(start: () -> Unit): Boolean {
        if (stoppingOwner != null) {
            queuedStart = start
            return false
        }
        return true
    }

    fun onDestroyed(owner: Long): (() -> Unit)? {
        val waiting = stoppingOwner ?: return null
        if (owner != 0L && owner != waiting) return null
        stoppingOwner = null
        lastBoundOwner = 0L
        val start = queuedStart
        queuedStart = null
        return start
    }
}

internal data class ServiceStoppedDispatch(
    val finishLiveAttempt: Boolean,
    val deferredStart: (() -> Unit)?,
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
