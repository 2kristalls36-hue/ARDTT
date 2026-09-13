package com.ardtt.app.core

import kotlinx.coroutines.CompletableDeferred

/**
 * Owns one user Connect request and the probe round that may satisfy it.
 *
 * Button, widget and tile share this path so an early ordinary-success cannot
 * re-enter the wait queue of the same job, and a Stop revokes late callbacks
 * even if the coroutine is still running.
 */
enum class ConnectEntryPoint {
    Button,
    Widget,
}

enum class ProbeRole {
    ConnectInitial,
    IdleDiagnostic,
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
    private var active: Request? = null
    private var probe: Probe? = null

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
    )

    private data class Probe(
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
            ctx.uiState == ConnState.PausedTrustedWifi
        ) {
            return ConnectLaunchAction.Ignore
        }
        val existing = active?.takeIf { !it.revoked }
        if (existing != null) {
            if (existing.launched || existing.continued) return ConnectLaunchAction.Ignore
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
            if (req == null || req.id != requestId || req.revoked) {
                return ConnectLaunchAction.Ignore
            }
            if (req.launched || req.continued) return ConnectLaunchAction.Ignore
            if (ctx.uiState == ConnState.Connecting || ctx.uiState == ConnState.Connected) {
                return ConnectLaunchAction.Ignore
            }
            if (!shouldConnectAfterProbeJoin(ctx.uiState) && ctx.uiState != ConnState.Probing) {
                return ConnectLaunchAction.Ignore
            }
            if (ctx.profileId != null && req.profileId != null && ctx.profileId != req.profileId) {
                return ConnectLaunchAction.Ignore
            }
            if (
                ctx.underlayKey != null &&
                req.networkKey != null &&
                !req.networkKey.samePhysicalNetwork(ctx.underlayKey)
            ) {
                return ConnectLaunchAction.Ignore
            }
            if (ctx.mode != req.mode) return ConnectLaunchAction.Ignore
            if (ctx.transportStartingOrLive) return ConnectLaunchAction.Ignore
            return proceedLocked(req, ctx)
        }

    suspend fun awaitDecision(requestId: Long) {
        val deferred = synchronized(lock) {
            active?.takeIf { it.id == requestId && !it.revoked }?.decision
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
            val ownedRequest = requestId ?: active?.takeIf { !it.revoked }?.id
            val resolvedRole = if (ownedRequest != null) ProbeRole.ConnectInitial else role
            probe = Probe(
                requestId = if (resolvedRole == ProbeRole.ConnectInitial) ownedRequest else null,
                role = resolvedRole,
                seriesId = seriesId,
                sessionEpoch = sessionEpoch,
                networkEpoch = networkEpoch,
                profileId = profileId,
                networkKey = networkKey,
            )
        }
    }

    fun retainInFlightProbe() {
        synchronized(lock) {
            val req = active?.takeIf { !it.revoked } ?: return
            val current = probe
            if (current != null && !current.revoked) {
                current.requestId = req.id
                current.role = ProbeRole.ConnectInitial
            }
        }
    }

    fun markLaunched(requestId: Long) {
        synchronized(lock) {
            val req = active ?: return
            if (req.id == requestId) req.launched = true
        }
    }

    fun revokeConnectWork(): ConnectRevokeResult = synchronized(lock) {
        val req = active
        req?.revoked = true
        req?.decision?.cancel()
        val current = probe
        val cancelProbe = current != null &&
            !current.revoked &&
            (current.role == ProbeRole.ConnectInitial || current.requestId == req?.id)
        if (cancelProbe) current?.revoked = true
        active = null
        ConnectRevokeResult(cancelProbe = cancelProbe, cancelWait = true)
    }

    fun revokeIdleDiagnostic() {
        synchronized(lock) {
            val current = probe ?: return
            if (current.role == ProbeRole.IdleDiagnostic) current.revoked = true
        }
    }

    fun admitEarly(cb: ProbeCallback): ProbeAdmitResult = synchronized(lock) {
        if (!matchesLocked(cb)) return rejected()
        if (probe?.revoked == true || cb.userStop) return rejected()
        if (cb.ordinarySuccess) {
            completeDecisionLocked(ProbeDecisionKind.OrdinarySuccess)
        }
        val applyReducer = cb.wantsConnected && !cb.userStop
        ProbeAdmitResult(
            accepted = true,
            completeWait = cb.ordinarySuccess,
            decisionKind = if (cb.ordinarySuccess) ProbeDecisionKind.OrdinarySuccess else null,
            applyToReducer = applyReducer,
            idleFold = false,
            allowReadyUi = false,
        )
    }

    fun admitFinal(cb: ProbeCallback): ProbeAdmitResult = synchronized(lock) {
        if (!matchesLocked(cb)) return rejected()
        val current = probe ?: return rejected()
        if (current.revoked) return rejected()
        if (current.finalApplied &&
            current.seriesId.isNotEmpty() &&
            cb.seriesId == current.seriesId
        ) {
            return rejected()
        }
        val req = active
        if (cb.userStop || req?.revoked == true && current.role == ProbeRole.ConnectInitial) {
            return rejected()
        }
        current.finalApplied = true
        if (cb.seriesId.isNotEmpty()) current.seriesId = cb.seriesId
        completeDecisionLocked(ProbeDecisionKind.RoundFinished)
        val disconnecting = cb.uiState == ConnState.Disconnecting
        if (cb.wantsConnected && !cb.userStop) {
            return ProbeAdmitResult(
                accepted = true,
                completeWait = true,
                decisionKind = ProbeDecisionKind.RoundFinished,
                applyToReducer = true,
                idleFold = false,
                allowReadyUi = false,
            )
        }
        if (current.role == ProbeRole.IdleDiagnostic && !cb.userStop) {
            return ProbeAdmitResult(
                accepted = true,
                completeWait = true,
                decisionKind = ProbeDecisionKind.RoundFinished,
                applyToReducer = false,
                idleFold = true,
                allowReadyUi = !disconnecting,
            )
        }
        if (current.role == ProbeRole.ConnectInitial && req != null && !req.revoked) {
            return ProbeAdmitResult(
                accepted = true,
                completeWait = true,
                decisionKind = ProbeDecisionKind.RoundFinished,
                applyToReducer = false,
                idleFold = true,
                allowReadyUi = false,
            )
        }
        rejected()
    }

    fun activeRequestId(): Long? = synchronized(lock) { active?.takeIf { !it.revoked }?.id }

    fun inheritEpochIfProbeInFlight(probeJobActive: Boolean): Long? = synchronized(lock) {
        val req = active?.takeIf { !it.revoked } ?: return null
        val current = probe ?: return null
        if (current.revoked || !probeJobActive) return null
        if (current.role != ProbeRole.ConnectInitial) return null
        if (current.requestId != null && current.requestId != req.id) return null
        current.sessionEpoch
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
        val current = probe ?: return null
        if (current.revoked || !probeJobActive) return null
        if (current.role != ProbeRole.ConnectInitial) return null
        if (current.requestId != null && current.requestId != req.id) return null
        return current.sessionEpoch
    }

    private fun attachIdleProbeToRequestLocked(request: Request) {
        val current = probe ?: return
        if (current.revoked || current.finalApplied) return
        current.requestId = request.id
        current.role = ProbeRole.ConnectInitial
    }

    private fun completeDecisionLocked(kind: ProbeDecisionKind) {
        val req = active ?: return
        if (req.revoked) return
        val current = probe
        if (current?.requestId != null && current.requestId != req.id) return
        if (!req.decision.isCompleted) {
            req.decision.complete(kind)
        }
    }

    private fun matchesLocked(cb: ProbeCallback): Boolean {
        val current = probe ?: return false
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
}
