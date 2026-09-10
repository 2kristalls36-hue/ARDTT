package com.ardtt.app.core

sealed class ConnectionEvent {
    data class UserConnect(
        val mode: ConnPathMode,
        val profileId: String?,
        val hasCallHash: Boolean,
        val silentRecreate: Boolean,
        val callIdentityToken: String = "",
    ) : ConnectionEvent()

    data object UserDisconnect : ConnectionEvent()
    data object UserCancelWait : ConnectionEvent()
    data object UserRetryNow : ConnectionEvent()
    data class PathModeChanged(val mode: ConnPathMode) : ConnectionEvent()

    data class UnderlayUpdated(val snapshot: UnderlaySnapshot) : ConnectionEvent()

    data class ProbeFinished(
        val evidence: ReachabilityEvidence,
        val sessionEpoch: Long,
        val networkEpoch: Long,
    ) : ConnectionEvent()

    data class DirectConfirmed(
        val sessionEpoch: Long,
        val transportEpoch: Long,
        val networkKey: NetworkKey?,
        val probeConfirmed: Boolean = false,
        val pathConfirmed: Boolean = false,
        val protocolReady: Boolean = false,
        val callEpoch: Long = 0L,
    ) : ConnectionEvent()

    data class DirectFailed(
        val sessionEpoch: Long,
        val transportEpoch: Long,
        val networkKey: NetworkKey?,
        val reason: String,
        val callEpoch: Long = 0L,
    ) : ConnectionEvent()

    data class BypassConfirmed(
        val sessionEpoch: Long,
        val transportEpoch: Long,
        val probeConfirmed: Boolean = false,
        val pathConfirmed: Boolean = false,
        val protocolReady: Boolean = false,
        val backendRunning: Boolean = false,
        val callEpoch: Long = 0L,
    ) : ConnectionEvent()

    data class BypassFailed(
        val sessionEpoch: Long,
        val transportEpoch: Long,
        val reason: String,
        val userAction: UserActionKind? = null,
        val callEpoch: Long = 0L,
    ) : ConnectionEvent()

    data class ParkedProcessDied(
        val sessionEpoch: Long,
    ) : ConnectionEvent()

    data class TransportDied(
        val sessionEpoch: Long,
        val transportEpoch: Long,
        val path: VpnPath?,
    ) : ConnectionEvent()

    data class CallValidityChanged(
        val sessionEpoch: Long,
        val validity: CallValidity,
    ) : ConnectionEvent()

    data class SessionParamsChanged(
        val profileId: String?,
        val hasCallHash: Boolean,
        val identityToken: String = "",
    ) : ConnectionEvent()

    data class CallIdentityChanged(
        val profileId: String?,
        val hashPresent: Boolean,
        val identityToken: String,
    ) : ConnectionEvent()

    data class Clock(val elapsedMs: Long) : ConnectionEvent()
    data class TrustedWifiChanged(val waiting: Boolean) : ConnectionEvent()
}

sealed class RecoveryCommand {
    data object None : RecoveryCommand()
    data object StopAll : RecoveryCommand()
    data object Probe : RecoveryCommand()
    data class StartDirect(val keepCall: Boolean) : RecoveryCommand()
    data class StartBypass(val reuseCall: Boolean) : RecoveryCommand()
    data object ParkBypassForDirect : RecoveryCommand()
    data object ResumeParkedRaw : RecoveryCommand()
    data object RebuildRawSameCall : RecoveryCommand()
    data object RefreshCredentials : RecoveryCommand()
    data object PauseNetOps : RecoveryCommand()
    data class ScheduleRetry(val delayMs: Long) : RecoveryCommand()
    data class ScheduleReeval(val delayMs: Long) : RecoveryCommand()
    data class DiscardStaleCall(
        val staleCallEpoch: Long,
        val staleIdentityToken: String,
        val stopActive: Boolean,
        val then: RecoveryCommand = None,
    ) : RecoveryCommand()
}

data class ReduceResult(
    val state: ConnectionSnapshot,
    val command: RecoveryCommand,
    val diagnostic: SessionDiagnostic? = null,
)

/**
 * Single owner of recovery decisions. Network I/O stays outside the reducer.
 */
object ConnectionReducer {
    fun reduce(
        state: ConnectionSnapshot,
        event: ConnectionEvent,
        elapsedMs: Long,
        jitterPermille: Int = 0,
    ): ReduceResult {
        return when (event) {
            is ConnectionEvent.UserDisconnect,
            ConnectionEvent.UserCancelWait,
            -> disconnect(state)
            is ConnectionEvent.UserConnect -> connect(state, event, elapsedMs)
            is ConnectionEvent.PathModeChanged ->
                state.copy(intent = state.intent.copy(mode = event.mode)).let {
                    if (it.intent.wantsConnected) decideNext(it, elapsedMs, jitterPermille) else ReduceResult(it, RecoveryCommand.None)
                }
            is ConnectionEvent.UserRetryNow -> retryNow(state, elapsedMs, jitterPermille)
            is ConnectionEvent.UnderlayUpdated -> onUnderlay(state, event.snapshot, elapsedMs, jitterPermille)
            is ConnectionEvent.ProbeFinished -> onProbe(state, event, elapsedMs, jitterPermille)
            is ConnectionEvent.DirectConfirmed -> onDirectOk(state, event)
            is ConnectionEvent.DirectFailed -> onDirectFailed(state, event, elapsedMs, jitterPermille)
            is ConnectionEvent.BypassConfirmed -> onBypassOk(state, event, elapsedMs)
            is ConnectionEvent.BypassFailed -> onBypassFailed(state, event, elapsedMs, jitterPermille)
            is ConnectionEvent.ParkedProcessDied -> onParkedDied(state, event)
            is ConnectionEvent.TransportDied -> onTransportDied(state, event, elapsedMs, jitterPermille)
            is ConnectionEvent.CallValidityChanged -> onCallValidity(state, event, elapsedMs, jitterPermille)
            is ConnectionEvent.SessionParamsChanged -> onSessionParams(state, event, elapsedMs, jitterPermille)
            is ConnectionEvent.CallIdentityChanged -> onCallIdentity(state, event, elapsedMs, jitterPermille)
            is ConnectionEvent.Clock -> onClock(state, event.elapsedMs, jitterPermille)
            is ConnectionEvent.TrustedWifiChanged ->
                state.copy(
                    ui = connectionUiModel(
                        phase = if (event.waiting) RecoveryPhase.Idle else state.recovery.phase,
                        activePath = state.activePath,
                        restriction = state.evidence?.restriction ?: RestrictionHint.Unknown,
                        transport = state.transport,
                        retryInMs = null,
                        trustedWifi = event.waiting,
                    ),
                ).let { ReduceResult(it, RecoveryCommand.None) }
        }
    }

    private fun disconnect(state: ConnectionSnapshot): ReduceResult {
        val next = ConnectionSnapshot(
            intent = state.intent.copy(wantsConnected = false),
            underlay = state.underlay,
            evidence = state.evidence,
            call = CallSessionState(
                hashPresent = state.call.hashPresent,
                validity = state.call.validity,
                profileId = state.call.profileId,
            ),
            sessionEpoch = state.sessionEpoch + 1L,
            networkEpoch = state.networkEpoch,
            transportEpoch = state.transportEpoch + 1L,
            recovery = RecoveryState(
                phase = RecoveryPhase.Idle,
                permit = RecoveryPermit(
                    sessionEpoch = state.sessionEpoch + 1L,
                    networkEpoch = state.networkEpoch,
                    transportEpoch = state.transportEpoch + 1L,
                    netOpsAllowed = false,
                    userStop = true,
                ),
            ),
            ui = connectionUiModel(
                phase = RecoveryPhase.Idle,
                activePath = null,
                restriction = RestrictionHint.Unknown,
                transport = TransportLifecycle.Stopped,
                retryInMs = null,
            ),
        )
        return ReduceResult(next, RecoveryCommand.StopAll)
    }

    private fun connect(
        state: ConnectionSnapshot,
        event: ConnectionEvent.UserConnect,
        elapsedMs: Long,
    ): ReduceResult {
        val sessionEpoch = state.sessionEpoch + 1L
        val transportEpoch = state.transportEpoch + 1L
        val identityChanged = event.callIdentityToken.isNotEmpty() &&
            event.callIdentityToken != state.call.identityToken
        val started = state.copy(
            intent = UserConnectionIntent(
                wantsConnected = true,
                mode = event.mode,
                profileId = event.profileId,
                hasCallHash = event.hasCallHash,
                silentRecreate = event.silentRecreate,
            ),
            call = state.call.copy(
                hashPresent = event.hasCallHash,
                profileId = event.profileId,
                createdThisGeneration = false,
                identityToken = event.callIdentityToken.ifEmpty { state.call.identityToken },
                callEpoch = if (identityChanged) state.call.callEpoch + 1L else state.call.callEpoch,
                validity = when {
                    !event.hasCallHash -> CallValidity.Valid
                    identityChanged -> CallValidity.Valid
                    else -> state.call.validity
                },
            ),
            parkedRawAlive = if (identityChanged) false else state.parkedRawAlive,
            sessionEpoch = sessionEpoch,
            transportEpoch = transportEpoch,
            wifiFailStreak = 0,
            wifiStableHits = 0,
            directNegative = null,
            lastConfirmedPath = null,
            recovery = RecoveryState(phase = RecoveryPhase.Probing),
        )
        val permitted = started.copy(
            recovery = started.recovery.copy(
                permit = permitFrom(started, netOps = true, inFlight = false),
            ),
        )
        return decideNext(permitted, elapsedMs, jitterPermille = 0)
    }

    private fun retryNow(
        state: ConnectionSnapshot,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        if (!state.intent.wantsConnected || state.recovery.permit.userStop) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        if (!state.underlay.allowsNetworkOps) {
            return decideNext(state, elapsedMs, jitterPermille)
        }
        if (state.recovery.inFlight) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        return decideNext(
            state.copy(
                recovery = state.recovery.copy(
                    nextRetryAtElapsedMs = null,
                    failureIndex = state.recovery.failureIndex,
                ),
                transport = if (state.transport == TransportLifecycle.Failed) {
                    TransportLifecycle.Stopped
                } else {
                    state.transport
                },
            ),
            elapsedMs,
            jitterPermille,
        )
    }

    private fun onUnderlay(
        state: ConnectionSnapshot,
        snapshot: UnderlaySnapshot,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        if (!state.intent.wantsConnected) {
            return ReduceResult(state.copy(underlay = snapshot, networkEpoch = snapshot.networkEpoch), RecoveryCommand.None)
        }
        val networkChanged = snapshot.key != state.underlay.key
        val leftWifiEpisode = !snapshot.kind.prefersDirectInAuto()
        val next = state.copy(
            underlay = snapshot,
            networkEpoch = snapshot.networkEpoch,
            directNegative = if (networkChanged) null else state.directNegative,
            evidence = if (networkChanged) null else state.evidence,
            wifiFailStreak = if (leftWifiEpisode) 0 else state.wifiFailStreak,
            wifiStableHits = if (leftWifiEpisode) 0 else state.wifiStableHits,
            call = if (snapshot.availability == UnderlayAvailability.None) {
                state.call.copy(
                    validity = if (state.call.validity == CallValidity.ConfirmedDead) {
                        CallValidity.ConfirmedDead
                    } else {
                        CallValidity.UnknownDueToNetwork
                    },
                )
            } else {
                state.call
            },
        )
        val fromNetworkGap = !state.underlay.allowsNetworkOps && snapshot.allowsNetworkOps
        val recovered = if (snapshot.allowsNetworkOps && (networkChanged || fromNetworkGap)) {
            next.copy(
                transport = if (next.transport == TransportLifecycle.Failed) {
                    TransportLifecycle.Stopped
                } else {
                    next.transport
                },
                recovery = next.recovery.copy(nextRetryAtElapsedMs = null),
            )
        } else {
            next
        }
        if (!snapshot.allowsNetworkOps) {
            val paused = next.copy(
                transport = if (next.transport == TransportLifecycle.Running) {
                    TransportLifecycle.Paused
                } else {
                    next.transport
                },
                recovery = next.recovery.copy(
                    inFlight = false,
                    phase = when (snapshot.availability) {
                        UnderlayAvailability.Suspended -> RecoveryPhase.NetworkSuspended
                        UnderlayAvailability.Captive -> RecoveryPhase.CaptivePortal
                        else -> RecoveryPhase.WaitingForNetwork
                    },
                    permit = permitFrom(next, netOps = false, inFlight = false),
                    nextRetryAtElapsedMs = null,
                ),
            )
            return ReduceResult(
                withUi(paused, elapsedMs),
                RecoveryCommand.PauseNetOps,
            )
        }
        return decideNext(recovered, elapsedMs, jitterPermille)
    }

    private fun onProbe(
        state: ConnectionSnapshot,
        event: ConnectionEvent.ProbeFinished,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        if (!state.recovery.permit.accepts(event.sessionEpoch, event.networkEpoch)) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        if (event.evidence.networkKey != null &&
            state.underlay.key != null &&
            event.evidence.networkKey != state.underlay.key
        ) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        val series = nextProbeSeriesCount(state.evidence, event.evidence, elapsedMs)
        val restriction = NetworkProbePolicy.restrictionHint(
            cellular = event.evidence.networkKey?.isCellular == true ||
                (event.evidence.networkKey == null && state.underlay.kind == UnderlayKind.Cellular),
            yandex = event.evidence.yandex,
            bigtech = event.evidence.bigtech,
            google = event.evidence.google,
            seriesCount = series,
        )
        val next = state.copy(
            evidence = event.evidence.copy(
                seriesCount = series,
                ttlUntilElapsedMs = elapsedMs + RecoverySettings.PROBE_RESTRICTION_TTL_MS,
                restriction = if (state.underlay.kind == UnderlayKind.Cellular) {
                    restriction
                } else {
                    RestrictionHint.None
                },
            ),
        )
        return decideNext(next, elapsedMs, jitterPermille)
    }

    private fun onDirectOk(
        state: ConnectionSnapshot,
        event: ConnectionEvent.DirectConfirmed,
    ): ReduceResult {
        if (!event.pathConfirmed && !event.protocolReady && !event.probeConfirmed) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        if (!state.recovery.permit.accepts(
                event.sessionEpoch,
                transportEpoch = event.transportEpoch,
                callEpoch = event.callEpoch,
            )
        ) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        if (event.networkKey != null &&
            state.underlay.key != null &&
            event.networkKey != state.underlay.key
        ) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        val next = state.copy(
            activePath = VpnPath.Direct,
            transport = TransportLifecycle.Running,
            lastConfirmedPath = VpnPath.Direct,
            wifiFailStreak = 0,
            wifiStableHits = state.wifiStableHits + 1,
            directNegative = null,
            recovery = state.recovery.copy(
                phase = RecoveryPhase.Connected,
                failureIndex = 0,
                inFlight = false,
                nextRetryAtElapsedMs = null,
                permit = permitFrom(state, netOps = true, inFlight = false),
                callOpInFlight = false,
            ),
        )
        return ReduceResult(withUi(next, nowElapsedMs = 0L), RecoveryCommand.None)
    }

    private fun onDirectFailed(
        state: ConnectionSnapshot,
        event: ConnectionEvent.DirectFailed,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        if (!state.recovery.permit.accepts(
                event.sessionEpoch,
                transportEpoch = event.transportEpoch,
                callEpoch = event.callEpoch,
            )
        ) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        val wifi = state.underlay.kind.prefersDirectInAuto()
        val retryAfter = elapsedMs + RecoverySettings.retryDelayMs(state.recovery.failureIndex, jitterPermille)
        val next = state.copy(
            transport = TransportLifecycle.Failed,
            directNegative = DirectNegativeEvidence(
                key = event.networkKey ?: state.underlay.key ?: NetworkKey(0L, state.underlay.kind, null, "unknown"),
                profileId = state.intent.profileId,
                reason = event.reason,
                failedAtElapsedMs = elapsedMs,
                retryAfterElapsedMs = retryAfter,
            ),
            wifiFailStreak = if (wifi) state.wifiFailStreak + 1 else state.wifiFailStreak,
            wifiStableHits = if (wifi) 0 else state.wifiStableHits,
        )
        return decideNext(next, elapsedMs, jitterPermille)
    }

    private fun onBypassOk(
        state: ConnectionSnapshot,
        event: ConnectionEvent.BypassConfirmed,
        elapsedMs: Long,
    ): ReduceResult {
        if (!event.pathConfirmed &&
            !event.protocolReady &&
            !event.probeConfirmed &&
            !event.backendRunning
        ) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        if (!state.recovery.permit.accepts(
                event.sessionEpoch,
                transportEpoch = event.transportEpoch,
                callEpoch = event.callEpoch,
            )
        ) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        val delay = if (state.intent.mode == ConnPathMode.Auto) {
            bypassReevalDelayMs(state.directNegative?.retryAfterElapsedMs, elapsedMs)
        } else {
            null
        }
        val next = state.copy(
            activePath = VpnPath.Bypass,
            transport = TransportLifecycle.Running,
            lastConfirmedPath = VpnPath.Bypass,
            parkedRawAlive = true,
            recovery = state.recovery.copy(
                phase = RecoveryPhase.Connected,
                failureIndex = 0,
                inFlight = false,
                nextRetryAtElapsedMs = delay?.let { elapsedMs + it },
                permit = permitFrom(state, netOps = true, inFlight = false),
                callOpInFlight = false,
            ),
        )
        val command = if (delay != null) {
            RecoveryCommand.ScheduleReeval(delay)
        } else {
            RecoveryCommand.None
        }
        return ReduceResult(withUi(next, nowElapsedMs = elapsedMs), command)
    }

    private fun onBypassFailed(
        state: ConnectionSnapshot,
        event: ConnectionEvent.BypassFailed,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        if (!state.recovery.permit.accepts(
                event.sessionEpoch,
                transportEpoch = event.transportEpoch,
                callEpoch = event.callEpoch,
            )
        ) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        if (event.userAction != null) {
            val next = state.copy(
                transport = TransportLifecycle.Failed,
                recovery = state.recovery.copy(
                    phase = RecoveryPhase.NeedsUserAction,
                    inFlight = false,
                    permit = permitFrom(state, netOps = false, inFlight = false),
                ),
                ui = connectionUiModel(
                    phase = RecoveryPhase.NeedsUserAction,
                    activePath = VpnPath.Bypass,
                    restriction = state.evidence?.restriction ?: RestrictionHint.Unknown,
                    transport = TransportLifecycle.Failed,
                    retryInMs = null,
                    userActionKind = event.userAction,
                ),
            )
            return ReduceResult(next, RecoveryCommand.None)
        }
        return decideNext(
            state.copy(transport = TransportLifecycle.Failed, parkedRawAlive = false),
            elapsedMs,
            jitterPermille,
        )
    }

    private fun onParkedDied(state: ConnectionSnapshot, event: ConnectionEvent.ParkedProcessDied): ReduceResult {
        if (event.sessionEpoch != state.sessionEpoch) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        return ReduceResult(
            state.copy(parkedRawAlive = false),
            RecoveryCommand.None,
        )
    }

    private fun onTransportDied(
        state: ConnectionSnapshot,
        event: ConnectionEvent.TransportDied,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        if (!state.recovery.permit.accepts(event.sessionEpoch, transportEpoch = event.transportEpoch)) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        if (!state.intent.wantsConnected) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        return decideNext(
            state.copy(transport = TransportLifecycle.Failed),
            elapsedMs,
            jitterPermille,
        )
    }

    private fun onCallValidity(
        state: ConnectionSnapshot,
        event: ConnectionEvent.CallValidityChanged,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        if (event.sessionEpoch != state.sessionEpoch) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        val next = state.copy(call = state.call.copy(validity = event.validity))
        if (event.validity == CallValidity.CredentialsExpired && state.intent.wantsConnected) {
            return ReduceResult(
                withUi(
                    next.copy(recovery = next.recovery.copy(callOpInFlight = true)),
                    elapsedMs,
                ),
                RecoveryCommand.RefreshCredentials,
                diagnostic = SessionDiagnostic.CredentialsRefreshed,
            )
        }
        return decideNext(next, elapsedMs, jitterPermille)
    }

    private fun onSessionParams(
        state: ConnectionSnapshot,
        event: ConnectionEvent.SessionParamsChanged,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        val profileChanged = state.intent.profileId != event.profileId
        val identityChanged = event.identityToken.isNotEmpty() &&
            event.identityToken != state.call.identityToken
        if (profileChanged || identityChanged) {
            return invalidateCallSession(
                state = state,
                elapsedMs = elapsedMs,
                jitterPermille = jitterPermille,
                profileId = event.profileId,
                hashPresent = event.hasCallHash,
                identityToken = event.identityToken.ifEmpty { state.call.identityToken },
                identityChanged = identityChanged || profileChanged,
            )
        }
        val next = state.copy(
            intent = state.intent.copy(
                profileId = event.profileId,
                hasCallHash = event.hasCallHash,
            ),
            call = state.call.copy(
                hashPresent = event.hasCallHash,
                profileId = event.profileId,
            ),
        )
        if (!next.intent.wantsConnected) {
            return ReduceResult(next, RecoveryCommand.None)
        }
        return decideNext(next, elapsedMs, jitterPermille)
    }

    private fun onCallIdentity(
        state: ConnectionSnapshot,
        event: ConnectionEvent.CallIdentityChanged,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        val identityChanged = event.identityToken != state.call.identityToken
        if (!identityChanged) {
            val next = state.copy(
                intent = state.intent.copy(
                    profileId = event.profileId ?: state.intent.profileId,
                    hasCallHash = event.hashPresent,
                ),
                call = state.call.copy(
                    hashPresent = event.hashPresent,
                    profileId = event.profileId ?: state.call.profileId,
                ),
            )
            if (!next.intent.wantsConnected) {
                return ReduceResult(next, RecoveryCommand.None)
            }
            return decideNext(next, elapsedMs, jitterPermille)
        }
        return invalidateCallSession(
            state = state,
            elapsedMs = elapsedMs,
            jitterPermille = jitterPermille,
            profileId = event.profileId ?: state.intent.profileId,
            hashPresent = event.hashPresent,
            identityToken = event.identityToken,
            identityChanged = true,
        )
    }

    private fun invalidateCallSession(
        state: ConnectionSnapshot,
        elapsedMs: Long,
        jitterPermille: Int,
        profileId: String?,
        hashPresent: Boolean,
        identityToken: String,
        identityChanged: Boolean,
    ): ReduceResult {
        val staleEpoch = state.call.callEpoch
        val staleToken = state.call.identityToken
        val transportEpoch = state.transportEpoch + 1L
        val keepDirect = state.activePath == VpnPath.Direct &&
            (state.transport == TransportLifecycle.Running ||
                state.transport == TransportLifecycle.Starting) &&
            state.intent.mode != ConnPathMode.Bypass
        val stopActive = !keepDirect &&
            (state.activePath == VpnPath.Bypass ||
                state.parkedRawAlive ||
                state.transport == TransportLifecycle.Starting)
        val nextCall = CallSessionState(
            hashPresent = hashPresent,
            validity = if (identityChanged) CallValidity.Valid else state.call.validity,
            profileId = profileId,
            identityToken = identityToken,
            callEpoch = if (identityChanged) state.call.callEpoch + 1L else state.call.callEpoch,
        )
        val next = state.copy(
            intent = state.intent.copy(
                profileId = profileId,
                hasCallHash = hashPresent,
            ),
            call = nextCall,
            parkedRawAlive = false,
            transportEpoch = transportEpoch,
            activePath = if (keepDirect) state.activePath else null,
            transport = if (keepDirect) state.transport else TransportLifecycle.Stopped,
            recovery = state.recovery.copy(
                inFlight = keepDirect && state.recovery.inFlight,
                callOpInFlight = false,
                permit = permitFrom(
                    state.copy(
                        transportEpoch = transportEpoch,
                        call = nextCall,
                    ),
                    netOps = state.underlay.allowsNetworkOps,
                    inFlight = keepDirect && state.recovery.inFlight,
                ),
            ),
        )
        val thenResult = if (!next.intent.wantsConnected || keepDirect) {
            null
        } else {
            decideNext(next, elapsedMs, jitterPermille)
        }
        return ReduceResult(
            if (keepDirect) withUi(next, elapsedMs) else (thenResult?.state ?: next),
            RecoveryCommand.DiscardStaleCall(
                staleCallEpoch = staleEpoch,
                staleIdentityToken = staleToken,
                stopActive = stopActive || state.parkedRawAlive,
                then = thenResult?.command ?: RecoveryCommand.None,
            ),
        )
    }

    private fun onClock(
        state: ConnectionSnapshot,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        val due = state.recovery.nextRetryAtElapsedMs
        if (!state.intent.wantsConnected) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        if (due == null || elapsedMs < due) {
            return ReduceResult(withUi(state, elapsedMs), RecoveryCommand.None)
        }
        return decideNext(
            state.copy(
                recovery = state.recovery.copy(nextRetryAtElapsedMs = null),
                transport = if (state.transport == TransportLifecycle.Failed) {
                    TransportLifecycle.Stopped
                } else {
                    state.transport
                },
            ),
            elapsedMs,
            jitterPermille,
            reevalDue = true,
        )
    }

    private fun decideNext(
        state: ConnectionSnapshot,
        elapsedMs: Long,
        jitterPermille: Int,
        reevalDue: Boolean = false,
    ): ReduceResult {
        if (!state.intent.wantsConnected || state.recovery.permit.userStop) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        val decision = decideAutoPath(
            AutoPathInput(
                mode = state.intent.mode,
                underlay = state.underlay,
                evidence = state.evidence,
                currentPath = state.activePath,
                transport = state.transport,
                hasCallHash = state.intent.hasCallHash,
                call = state.call,
                directNegative = state.directNegative,
                lastConfirmedPath = state.lastConfirmedPath,
                wifiFailStreak = state.wifiFailStreak,
                wifiStableHits = state.wifiStableHits,
                parkedRawAlive = state.parkedRawAlive,
                elapsedMs = elapsedMs,
                profileId = state.intent.profileId,
                reevalDue = reevalDue,
            ),
        )
        return applyDecision(state, decision, elapsedMs, jitterPermille)
    }

    private fun applyDecision(
        state: ConnectionSnapshot,
        decision: AutoDecision,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        fun phaseOf(phase: RecoveryPhase, inFlight: Boolean = false, delay: Long? = null) =
            state.recovery.copy(
                phase = phase,
                inFlight = inFlight,
                nextRetryAtElapsedMs = delay?.let { elapsedMs + it },
                permit = permitFrom(
                    state,
                    netOps = state.underlay.allowsNetworkOps,
                    inFlight = inFlight,
                ),
            )

        if (state.recovery.inFlight) {
            val samePathInFlight = when (decision) {
                is AutoDecision.StartDirect ->
                    state.activePath == VpnPath.Direct &&
                        state.transport == TransportLifecycle.Starting
                is AutoDecision.StartBypass ->
                    state.activePath == VpnPath.Bypass &&
                        state.transport == TransportLifecycle.Starting
                is AutoDecision.Stay -> true
                else -> false
            }
            if (samePathInFlight) {
                return ReduceResult(withUi(state, elapsedMs), RecoveryCommand.None)
            }
        }

        return when (decision) {
            AutoDecision.WaitForUnderlay -> ReduceResult(
                withUi(state.copy(recovery = phaseOf(RecoveryPhase.WaitingForNetwork)), elapsedMs),
                RecoveryCommand.PauseNetOps,
            )
            AutoDecision.WaitSuspended -> ReduceResult(
                withUi(state.copy(recovery = phaseOf(RecoveryPhase.NetworkSuspended)), elapsedMs),
                RecoveryCommand.PauseNetOps,
            )
            AutoDecision.WaitUnknownKind -> ReduceResult(
                withUi(state.copy(recovery = phaseOf(RecoveryPhase.WaitingForNetwork)), elapsedMs),
                RecoveryCommand.PauseNetOps,
            )
            AutoDecision.ShowCaptive -> ReduceResult(
                withUi(state.copy(recovery = phaseOf(RecoveryPhase.CaptivePortal)), elapsedMs),
                RecoveryCommand.None,
            )
            is AutoDecision.NeedsUserAction -> ReduceResult(
                state.copy(
                    recovery = phaseOf(RecoveryPhase.NeedsUserAction),
                    ui = connectionUiModel(
                        phase = RecoveryPhase.NeedsUserAction,
                        activePath = state.activePath,
                        restriction = state.evidence?.restriction ?: RestrictionHint.Unknown,
                        transport = state.transport,
                        retryInMs = null,
                        userActionKind = decision.kind,
                    ),
                ),
                RecoveryCommand.None,
            )
            is AutoDecision.ServerFault -> {
                val delay = RecoverySettings.retryDelayMs(state.recovery.failureIndex, jitterPermille)
                ReduceResult(
                    withUi(
                        state.copy(
                            recovery = phaseOf(
                                RecoveryPhase.Backoff,
                                delay = delay,
                            ).copy(failureIndex = state.recovery.failureIndex + 1),
                        ),
                        elapsedMs,
                    ),
                    RecoveryCommand.ScheduleRetry(delay),
                )
            }
            is AutoDecision.Backoff -> {
                val delay = RecoverySettings.retryDelayMs(state.recovery.failureIndex, jitterPermille)
                ReduceResult(
                    withUi(
                        state.copy(
                            recovery = phaseOf(RecoveryPhase.Backoff, delay = delay)
                                .copy(failureIndex = state.recovery.failureIndex + 1),
                        ),
                        elapsedMs,
                    ),
                    RecoveryCommand.ScheduleRetry(delay),
                )
            }
            is AutoDecision.Stay -> {
                if (decision.reason == "wifi-hysteresis" &&
                    state.recovery.nextRetryAtElapsedMs == null
                ) {
                    val delay = RecoverySettings.DIRECT_REEVAL_WHILE_BYPASS_MS
                    ReduceResult(
                        withUi(
                            state.copy(
                                recovery = state.recovery.copy(
                                    nextRetryAtElapsedMs = elapsedMs + delay,
                                ),
                            ),
                            elapsedMs,
                        ),
                        RecoveryCommand.ScheduleReeval(delay),
                    )
                } else {
                    ReduceResult(withUi(state, elapsedMs), RecoveryCommand.None)
                }
            }
            is AutoDecision.StartDirect -> {
                backoffAfterFailure(VpnPath.Direct, state, elapsedMs, jitterPermille)?.let { return it }
                val transportEpoch = state.transportEpoch + 1L
                val switching = state.activePath == VpnPath.Bypass &&
                    (state.transport == TransportLifecycle.Running ||
                        state.transport == TransportLifecycle.Starting ||
                        state.parkedRawAlive)
                val cmd = if (switching) {
                    RecoveryCommand.ParkBypassForDirect
                } else {
                    RecoveryCommand.StartDirect(keepCall = decision.keepCall)
                }
                val phase = if (switching) {
                    RecoveryPhase.SwitchingToWifi
                } else {
                    RecoveryPhase.ConnectingDirect
                }
                ReduceResult(
                    withUi(
                        state.copy(
                            activePath = VpnPath.Direct,
                            transport = TransportLifecycle.Starting,
                            transportEpoch = transportEpoch,
                            recovery = phaseOf(phase, inFlight = true).copy(
                                permit = permitFrom(
                                    state.copy(transportEpoch = transportEpoch),
                                    netOps = true,
                                    inFlight = true,
                                ),
                            ),
                        ),
                        elapsedMs,
                    ),
                    cmd,
                )
            }
            is AutoDecision.StartBypass -> {
                backoffAfterFailure(VpnPath.Bypass, state, elapsedMs, jitterPermille)?.let { return it }
                val transportEpoch = state.transportEpoch + 1L
                val returning = decision.reason.contains("return-mobile") ||
                    (state.parkedRawAlive && state.activePath == VpnPath.Direct)
                val cmd = when {
                    state.parkedRawAlive -> RecoveryCommand.ResumeParkedRaw
                    state.call.validity == CallValidity.CredentialsExpired ->
                        RecoveryCommand.RefreshCredentials
                    returning && state.call.canReuse -> RecoveryCommand.RebuildRawSameCall
                    else -> RecoveryCommand.StartBypass(reuseCall = decision.reuseCall)
                }
                val diagnostic = when (cmd) {
                    RecoveryCommand.ResumeParkedRaw -> SessionDiagnostic.TransportResumed
                    is RecoveryCommand.StartBypass ->
                        if (decision.reuseCall) SessionDiagnostic.CallReused else SessionDiagnostic.CallCreated
                    RecoveryCommand.RefreshCredentials -> SessionDiagnostic.CredentialsRefreshed
                    else -> SessionDiagnostic.TransportRebuilt
                }
                val phase = if (returning) {
                    RecoveryPhase.ReturningToMobile
                } else {
                    RecoveryPhase.ConnectingBypass
                }
                ReduceResult(
                    withUi(
                        state.copy(
                            activePath = VpnPath.Bypass,
                            transport = TransportLifecycle.Starting,
                            transportEpoch = transportEpoch,
                            recovery = phaseOf(phase, inFlight = true).copy(
                                permit = permitFrom(
                                    state.copy(transportEpoch = transportEpoch),
                                    netOps = true,
                                    inFlight = true,
                                ),
                            ),
                        ),
                        elapsedMs,
                    ),
                    cmd,
                    diagnostic = diagnostic,
                )
            }
        }
    }

    /**
     * First failure on this path schedules backoff. Later events while the
     * timer is still running must not bump [RecoveryState.failureIndex] or
     * start a second attempt. A due timer falls through to a real start.
     */
    private fun backoffAfterFailure(
        path: VpnPath,
        state: ConnectionSnapshot,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult? {
        if (state.transport != TransportLifecycle.Failed || state.activePath != path) {
            return null
        }
        val due = state.recovery.nextRetryAtElapsedMs
        if (due != null && elapsedMs < due) {
            return ReduceResult(withUi(state, elapsedMs), RecoveryCommand.None)
        }
        if (due != null) {
            return null
        }
        val delay = RecoverySettings.retryDelayMs(state.recovery.failureIndex, jitterPermille)
        return ReduceResult(
            withUi(
                state.copy(
                    recovery = state.recovery.copy(
                        phase = RecoveryPhase.Backoff,
                        inFlight = false,
                        nextRetryAtElapsedMs = elapsedMs + delay,
                        failureIndex = state.recovery.failureIndex + 1,
                        permit = permitFrom(
                            state,
                            netOps = state.underlay.allowsNetworkOps,
                            inFlight = false,
                        ),
                    ),
                ),
                elapsedMs,
            ),
            RecoveryCommand.ScheduleRetry(delay),
        )
    }

    private fun permitFrom(
        state: ConnectionSnapshot,
        netOps: Boolean,
        inFlight: Boolean,
    ): RecoveryPermit = RecoveryPermit(
        sessionEpoch = state.sessionEpoch,
        networkEpoch = state.networkEpoch,
        transportEpoch = state.transportEpoch,
        callEpoch = state.call.callEpoch,
        netOpsAllowed = netOps && state.intent.wantsConnected,
        recoveryInFlight = inFlight,
        callOpInFlight = state.recovery.callOpInFlight,
        userStop = !state.intent.wantsConnected,
    )

    private fun withUi(state: ConnectionSnapshot, nowElapsedMs: Long): ConnectionSnapshot {
        val retryAt = state.recovery.nextRetryAtElapsedMs
        val remaining = if (retryAt != null) (retryAt - nowElapsedMs).coerceAtLeast(0L) else null
        return state.copy(
            ui = connectionUiModel(
                phase = state.recovery.phase,
                activePath = state.activePath,
                restriction = state.evidence?.restriction ?: RestrictionHint.Unknown,
                transport = state.transport,
                retryInMs = remaining,
                underlayKind = state.underlay.kind,
            ),
        )
    }
}
