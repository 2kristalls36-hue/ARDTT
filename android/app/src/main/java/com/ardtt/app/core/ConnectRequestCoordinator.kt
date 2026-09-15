package com.ardtt.app.core

import kotlinx.coroutines.CompletableDeferred

/**
 * Owns user Connect requests and probe rounds that may satisfy them.
 *
 * Button, widget and tile share this path so an early ordinary-success cannot
 * re-enter the wait queue of the same job. Stop revokes specific series so a
 * later background diagnostic is not blocked by a stale revoked record.
 * Final evidence is applied before the waiter is released, so the first
 * path decision sees that fold.
 */
enum class ConnectEntryPoint {
    Button,
    Widget,
}

/** Error/Ready after a finished attempt must open a new UserConnect, not Stay. */
fun needsFreshUserConnect(uiState: ConnState, wantsConnected: Boolean): Boolean {
    if (!wantsConnected) return true
    return when (uiState) {
        ConnState.Error,
        ConnState.Ready,
        ConnState.Idle,
        ConnState.NeedsUserAction,
        -> true
        else -> false
    }
}

enum class ProbeRole {
    ConnectInitial,
    IdleDiagnostic,
    BackgroundDiagnostic,
}

enum class ProbeDecisionKind {
    OrdinarySuccess,
    RoundFinished,
}

data class ConnectLaunchContext(
    val entry: ConnectEntryPoint,
    val uiState: ConnState,
    val mode: ConnPathMode,
    val underlayKind: UnderlayKind,
    val underlayUsable: Boolean,
    val underlayKey: NetworkKey?,
    val profileId: String?,
    val sessionEpoch: Long,
    val networkEpoch: Long,
    val hasSameNetworkProbeEvidence: Boolean,
    val probeJobActive: Boolean,
    val transportStartingOrLive: Boolean,
    val hasCallHash: Boolean,
)

sealed class ConnectLaunchAction {
    data object Ignore : ConnectLaunchAction()
    data class EnqueueWait(
        val requestId: Long,
        val startProbe: Boolean,
        val alreadyWaiting: Boolean,
    ) : ConnectLaunchAction()
    data class Proceed(
        val requestId: Long,
        val inheritProbeSessionEpoch: Long?,
    ) : ConnectLaunchAction()
}

data class ConnectRevokeResult(
    val cancelProbe: Boolean,
    val cancelWait: Boolean,
)

data class ProbeCallback(
    val seriesId: String,
    val sessionEpoch: Long,
    val networkEpoch: Long,
    val profileId: String?,
    val networkKey: NetworkKey?,
    val ordinarySuccess: Boolean,
    val sample: RestrictionSample,
    val wantsConnected: Boolean,
    val uiState: ConnState,
    val liveNetworkKey: NetworkKey?,
    val liveProfileId: String?,
    val userStop: Boolean,
)

data class ProbeAdmitResult(
    val accepted: Boolean,
    val completeWait: Boolean = false,
    val decisionKind: ProbeDecisionKind? = null,
    val applyToReducer: Boolean = false,
    val idleFold: Boolean = false,
    val allowReadyUi: Boolean = false,
)

class ConnectRequestCoordinator {
    private val lock = Any()
    private var nextRequestId = 1L
    private var nextTransportOwner = 1L
    private var liveTransportOwner: Long? = null
    private val stoppedOwners = ArrayDeque<Long>()
    private var active: Request? = null
    private val ops = ArrayDeque<ProbeOp>()

    private data class Request(
        val id: Long,
        val entry: ConnectEntryPoint,
        val profileId: String?,
        val networkKey: NetworkKey?,
        val mode: ConnPathMode,
        val decision: CompletableDeferred<ProbeDecisionKind> = CompletableDeferred(),
        var revoked: Boolean = false,
        var launched: Boolean = false,
        var continued: Boolean = false,
        var finished: Boolean = false,
        var transportOwner: Long? = null,
    )

    private data class ProbeOp(
        var requestId: Long?,
        var role: ProbeRole,
        var seriesId: String,
        val sessionEpoch: Long,
        val networkEpoch: Long,
        val profileId: String?,
        val networkKey: NetworkKey?,
        var revoked: Boolean = false,
        var finalApplied: Boolean = false,
    )

    fun onConnectRequested(ctx: ConnectLaunchContext): ConnectLaunchAction = synchronized(lock) {
        if (
            ctx.uiState == ConnState.Connected ||
            ctx.uiState == ConnState.Connecting ||
            ctx.uiState == ConnState.PausedTrustedWifi ||
            ctx.uiState == ConnState.Disconnecting
        ) {
            return ConnectLaunchAction.Ignore
        }
        var existing = active?.takeIf { !it.revoked && !it.finished }
        if (existing != null && (existing.launched || existing.continued)) {
            if (!isTerminalForNewConnect(ctx)) return ConnectLaunchAction.Ignore
            finishAttemptLocked()
            existing = null
        }
        if (existing != null) {
            if (shouldWaitForDecision(ctx) && !existing.decision.isCompleted) {
                return ConnectLaunchAction.EnqueueWait(
                    requestId = existing.id,
                    startProbe = !ctx.probeJobActive,
                    alreadyWaiting = true,
                )
            }
            return proceedLocked(existing, ctx)
        }
        val request = Request(
            id = nextRequestId++,
            entry = ctx.entry,
            profileId = ctx.profileId,
            networkKey = ctx.underlayKey,
            mode = ctx.mode,
        )
        active = request
        attachIdleProbeToRequestLocked(request)
        if (shouldWaitForDecision(ctx)) {
            return ConnectLaunchAction.EnqueueWait(
                requestId = request.id,
                startProbe = !ctx.probeJobActive,
                alreadyWaiting = false,
            )
        }
        return proceedLocked(request, ctx)
    }

    fun continueAfterWait(ctx: ConnectLaunchContext, requestId: Long): ConnectLaunchAction =
        synchronized(lock) {
            val req = active
            if (req == null || req.id != requestId || req.revoked || req.finished) {
                return ConnectLaunchAction.Ignore
            }
            if (req.launched || req.continued) return ConnectLaunchAction.Ignore
            if (ctx.uiState == ConnState.Connecting || ctx.uiState == ConnState.Connected) {
                return ConnectLaunchAction.Ignore
            }
            if (!shouldConnectAfterProbeJoin(ctx.uiState) && ctx.uiState != ConnState.Probing) {
                finishAttemptLocked()
                return ConnectLaunchAction.Ignore
            }
            val scopeChanged =
                (ctx.profileId != null && req.profileId != null && ctx.profileId != req.profileId) ||
                    (
                        ctx.underlayKey != null &&
                            req.networkKey != null &&
                            !req.networkKey.samePhysicalNetwork(ctx.underlayKey)
                        ) ||
                    ctx.mode != req.mode
            if (scopeChanged) {
                finishAttemptLocked()
                return ConnectLaunchAction.Ignore
            }
            if (ctx.transportStartingOrLive) return ConnectLaunchAction.Ignore
            return proceedLocked(req, ctx)
        }

    suspend fun awaitDecision(requestId: Long) {
        val deferred = synchronized(lock) {
            active?.takeIf { it.id == requestId && !it.revoked && !it.finished }?.decision
        } ?: return
        deferred.await()
    }

    fun registerProbe(
        role: ProbeRole,
        seriesId: String,
        sessionEpoch: Long,
        networkEpoch: Long,
        profileId: String?,
        networkKey: NetworkKey?,
        requestId: Long? = null,
    ) {
        synchronized(lock) {
            val waiting = active?.takeIf { !it.revoked && !it.finished && !it.launched }
            val ownedRequest = requestId ?: waiting?.id
            val resolvedRole = when {
                role == ProbeRole.BackgroundDiagnostic -> ProbeRole.BackgroundDiagnostic
                ownedRequest != null && role != ProbeRole.BackgroundDiagnostic ->
                    ProbeRole.ConnectInitial
                else -> role
            }
            rememberOpLocked(
                ProbeOp(
                    requestId = ownedRequest,
                    role = resolvedRole,
                    seriesId = seriesId,
                    sessionEpoch = sessionEpoch,
                    networkEpoch = networkEpoch,
                    profileId = profileId,
                    networkKey = networkKey,
                ),
            )
        }
    }

    fun retainInFlightProbe() {
        synchronized(lock) {
            val req = active?.takeIf { !it.revoked && !it.finished } ?: return
            val current = liveConnectInitialLocked() ?: return
            if (current.revoked) return
            current.requestId = req.id
            current.role = ProbeRole.ConnectInitial
        }
    }

    fun markLaunched(requestId: Long) {
        synchronized(lock) {
            val req = active ?: return
            if (req.id == requestId) req.launched = true
        }
    }

    /**
     * The attempt ended (Error, service gone, Ready) without a user Stop.
     * Does not revoke probe series, so a still-legal initial final can idle-fold.
     * Pair with [ConnectionEvent.AttemptFailed] so leftover Starting cannot
     * overwrite Error or ignore the next Connect.
     */
    fun finishAttempt() {
        synchronized(lock) { finishAttemptLocked() }
    }

    /**
     * Stamp the Connect attempt that owns the live VPN transport.
     * This is not the Android Service instance and not a startId.
     * Late stop callbacks must send this same attempt id.
     */
    fun bindNewTransport(): Long = synchronized(lock) {
        val owner = nextTransportOwner++
        liveTransportOwner = owner
        active?.takeIf { !it.finished && !it.revoked }?.transportOwner = owner
        owner
    }

    /**
     * True when this stop belongs to the current Connect attempt.
     * A delayed ACTION_STOP/onDestroy of attempt A must not finish Connect B.
     */
    fun onOwnedServiceStopped(owner: Long): Boolean = synchronized(lock) {
        if (owner == 0L) return false
        if (stoppedOwners.contains(owner)) return false
        while (stoppedOwners.size >= 8) stoppedOwners.removeFirst()
        stoppedOwners.addLast(owner)
        val matchesLive = liveTransportOwner == owner
        if (matchesLive) liveTransportOwner = null
        val req = active?.takeIf { !it.finished && !it.revoked }
        if (req != null && req.transportOwner != owner) return false
        if (req == null) return matchesLive
        true
    }

    fun revokeConnectWork(): ConnectRevokeResult = synchronized(lock) {
        val req = active
        req?.revoked = true
        req?.finished = true
        req?.decision?.cancel()
        var cancelProbe = false
        for (op in ops) {
            if (!op.revoked) {
                op.revoked = true
                cancelProbe = true
            }
        }
        active = null
        ConnectRevokeResult(cancelProbe = cancelProbe, cancelWait = true)
    }

    fun revokeIdleDiagnostic() {
        synchronized(lock) {
            for (op in ops) {
                if (op.role == ProbeRole.IdleDiagnostic) op.revoked = true
            }
        }
    }

    fun lateCallbackBlocked(seriesId: String): Boolean = synchronized(lock) {
        if (seriesId.isEmpty()) return false
        ops.any { it.seriesId == seriesId && it.revoked }
    }

    fun admitEarly(cb: ProbeCallback): ProbeAdmitResult = synchronized(lock) {
        val current = findOpLocked(cb) ?: return rejected()
        if (!matchesLocked(current, cb)) return rejected()
        if (cb.ordinarySuccess) {
            completeDecisionLocked(ProbeDecisionKind.OrdinarySuccess)
        }
        ProbeAdmitResult(
            accepted = true,
            completeWait = cb.ordinarySuccess,
            decisionKind = if (cb.ordinarySuccess) ProbeDecisionKind.OrdinarySuccess else null,
            applyToReducer = cb.wantsConnected,
            idleFold = false,
            allowReadyUi = false,
        )
    }

    fun admitFinal(cb: ProbeCallback): ProbeAdmitResult = synchronized(lock) {
        val current = findOpLocked(cb) ?: return rejected()
        if (!matchesLocked(current, cb)) return rejected()
        if (current.finalApplied &&
            current.seriesId.isNotEmpty() &&
            cb.seriesId == current.seriesId
        ) {
            return rejected()
        }
        val req = active?.takeIf { !it.finished }
        if (req != null && req.revoked && current.role == ProbeRole.ConnectInitial) {
            return rejected()
        }
        current.finalApplied = true
        if (cb.seriesId.isNotEmpty()) current.seriesId = cb.seriesId
        val disconnecting = cb.uiState == ConnState.Disconnecting
        val liveRequest = req != null && !req.revoked
        if (cb.wantsConnected && liveRequest) {
            return ProbeAdmitResult(
                accepted = true,
                completeWait = current.role == ProbeRole.ConnectInitial &&
                    (current.requestId == null || current.requestId == req?.id),
                decisionKind = ProbeDecisionKind.RoundFinished,
                applyToReducer = true,
                idleFold = false,
                allowReadyUi = false,
            )
        }
        if (current.role == ProbeRole.IdleDiagnostic ||
            current.role == ProbeRole.BackgroundDiagnostic ||
            current.role == ProbeRole.ConnectInitial
        ) {
            return ProbeAdmitResult(
                accepted = true,
                completeWait = current.role == ProbeRole.ConnectInitial && liveRequest,
                decisionKind = ProbeDecisionKind.RoundFinished,
                applyToReducer = false,
                idleFold = true,
                allowReadyUi = !disconnecting && current.role == ProbeRole.IdleDiagnostic,
            )
        }
        rejected()
    }

    /**
     * Admit the final, apply evidence/counters, then release the Connect waiter.
     * Completing the deferred inside [admitFinal] would let Main.immediate
     * resume path selection before the fold.
     */
    fun admitAndApplyFinal(
        cb: ProbeCallback,
        apply: (ProbeAdmitResult) -> Unit,
    ): ProbeAdmitResult {
        val admit = admitFinal(cb)
        if (!admit.accepted) return admit
        apply(admit)
        if (admit.completeWait) {
            releaseWait(admit.decisionKind ?: ProbeDecisionKind.RoundFinished)
        }
        return admit
    }

    fun releaseWait(kind: ProbeDecisionKind) {
        synchronized(lock) { completeDecisionLocked(kind) }
    }

    fun activeRequestId(): Long? = synchronized(lock) {
        active?.takeIf { !it.revoked && !it.finished }?.id
    }

    fun liveTransportOwnerId(): Long = synchronized(lock) {
        liveTransportOwner ?: 0L
    }

    fun activeRequestTransportOwner(): Long = synchronized(lock) {
        active?.takeIf { !it.revoked && !it.finished }?.transportOwner ?: 0L
    }

    fun hasActiveConnectRequest(): Boolean = synchronized(lock) {
        active?.takeIf { !it.revoked && !it.finished } != null
    }

    fun inheritEpochIfProbeInFlight(probeJobActive: Boolean): Long? = synchronized(lock) {
        val req = active?.takeIf { !it.revoked && !it.finished } ?: return null
        inheritEpochIfProbeInFlightLocked(probeJobActive, req)
    }

    private fun isTerminalForNewConnect(ctx: ConnectLaunchContext): Boolean {
        if (ctx.transportStartingOrLive) return false
        return ctx.uiState == ConnState.Error ||
            ctx.uiState == ConnState.Ready ||
            ctx.uiState == ConnState.Idle
    }

    private fun shouldWaitForDecision(ctx: ConnectLaunchContext): Boolean {
        if (active?.decision?.isCompleted == true) return false
        return shouldWaitForCellularWhitelistProbe(
            mode = ctx.mode,
            underlayKind = ctx.underlayKind,
            state = ctx.uiState,
            hasSameNetworkProbeEvidence = ctx.hasSameNetworkProbeEvidence,
        )
    }

    private fun proceedLocked(req: Request, ctx: ConnectLaunchContext): ConnectLaunchAction {
        if (req.launched || req.continued) return ConnectLaunchAction.Ignore
        req.continued = true
        val inherit = inheritEpochIfProbeInFlightLocked(ctx.probeJobActive, req)
        return ConnectLaunchAction.Proceed(req.id, inherit)
    }

    private fun inheritEpochIfProbeInFlightLocked(probeJobActive: Boolean, req: Request): Long? {
        if (!probeJobActive) return null
        val current = liveConnectInitialLocked() ?: return null
        if (current.revoked) return null
        if (current.requestId != null && current.requestId != req.id) return null
        return current.sessionEpoch
    }

    private fun attachIdleProbeToRequestLocked(request: Request) {
        val current = ops.lastOrNull { !it.revoked && !it.finalApplied } ?: return
        if (current.role == ProbeRole.BackgroundDiagnostic) return
        current.requestId = request.id
        current.role = ProbeRole.ConnectInitial
    }

    private fun finishAttemptLocked() {
        val req = active ?: return
        req.finished = true
        if (!req.decision.isCompleted) req.decision.cancel()
        active = null
    }

    private fun completeDecisionLocked(kind: ProbeDecisionKind) {
        val req = active ?: return
        if (req.revoked || req.finished) return
        if (!req.decision.isCompleted) {
            req.decision.complete(kind)
        }
    }

    private fun rememberOpLocked(op: ProbeOp) {
        if (op.seriesId.isNotEmpty()) {
            ops.removeAll { it.seriesId == op.seriesId }
        }
        while (ops.size >= OP_LIMIT) ops.removeFirst()
        ops.addLast(op)
    }

    private fun findOpLocked(cb: ProbeCallback): ProbeOp? {
        if (cb.seriesId.isNotEmpty()) {
            ops.lastOrNull { it.seriesId == cb.seriesId }?.let { return it }
        }
        return ops.lastOrNull { op ->
            !op.revoked &&
                op.sessionEpoch == cb.sessionEpoch &&
                op.networkEpoch == cb.networkEpoch &&
                (op.seriesId.isEmpty() || cb.seriesId.isEmpty())
        }
    }

    private fun liveConnectInitialLocked(): ProbeOp? =
        ops.lastOrNull { it.role == ProbeRole.ConnectInitial && !it.revoked }

    private fun matchesLocked(current: ProbeOp, cb: ProbeCallback): Boolean {
        if (current.revoked) return false
        if (current.sessionEpoch != cb.sessionEpoch) return false
        if (current.networkEpoch != cb.networkEpoch) return false
        if (current.seriesId.isNotEmpty() &&
            cb.seriesId.isNotEmpty() &&
            current.seriesId != cb.seriesId
        ) {
            return false
        }
        if (current.profileId != null &&
            cb.liveProfileId != null &&
            current.profileId != cb.liveProfileId
        ) {
            return false
        }
        if (current.networkKey != null &&
            cb.liveNetworkKey != null &&
            !current.networkKey.samePhysicalNetwork(cb.liveNetworkKey)
        ) {
            return false
        }
        if (cb.seriesId.isNotEmpty() && current.seriesId.isEmpty()) {
            current.seriesId = cb.seriesId
        }
        return true
    }

    private fun rejected(): ProbeAdmitResult = ProbeAdmitResult(accepted = false)

    companion object {
        private const val OP_LIMIT = 8
    }
}
