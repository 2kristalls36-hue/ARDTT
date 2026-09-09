package com.ardtt.app.core

sealed class ConnectionEvent {
    data class UserConnect(
        val mode: ConnPathMode,
        val profileId: String?,
        val hasCallHash: Boolean,
        val silentRecreate: Boolean,
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
    ) : ConnectionEvent()

    data class DirectFailed(
        val sessionEpoch: Long,
        val transportEpoch: Long,
        val networkKey: NetworkKey?,
        val reason: String,
    ) : ConnectionEvent()

    data class BypassConfirmed(
        val sessionEpoch: Long,
        val transportEpoch: Long,
    ) : ConnectionEvent()

    data class BypassFailed(
        val sessionEpoch: Long,
        val transportEpoch: Long,
        val reason: String,
        val userAction: UserActionKind? = null,
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
            is ConnectionEvent.BypassConfirmed -> onBypassOk(state, event)
            is ConnectionEvent.BypassFailed -> onBypassFailed(state, event, elapsedMs, jitterPermille)
            is ConnectionEvent.ParkedProcessDied -> onParkedDied(state, event)
            is ConnectionEvent.TransportDied -> onTransportDied(state, event, elapsedMs, jitterPermille)
            is ConnectionEvent.CallValidityChanged -> onCallValidity(state, event, elapsedMs, jitterPermille)
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
            ),
            sessionEpoch = sessionEpoch,
            transportEpoch = state.transportEpoch + 1L,
            wifiFailStreak = 0,
            wifiStableHits = 0,
            directFailedOnNetwork = null,
            lastConfirmedPath = null,
            recovery = RecoveryState(
                phase = RecoveryPhase.Probing,
                permit = permit(state, sessionEpoch, netOps = true, inFlight = false),
            ),
        )
        return decideNext(started, elapsedMs, jitterPermille = 0)
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
        val next = state.copy(
            underlay = snapshot,
            networkEpoch = snapshot.networkEpoch,
            directFailedOnNetwork = if (networkChanged) null else state.directFailedOnNetwork,
            evidence = if (networkChanged) null else state.evidence,
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
                    permit = permit(next, next.sessionEpoch, netOps = false, inFlight = false),
                    nextRetryAtElapsedMs = null,
                ),
            )
            return ReduceResult(
                withUi(paused, elapsedMs),
                RecoveryCommand.PauseNetOps,
            )
        }
        return decideNext(next, elapsedMs, jitterPermille)
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
        val next = state.copy(evidence = event.evidence)
        return decideNext(next, elapsedMs, jitterPermille)
    }

    private fun onDirectOk(
        state: ConnectionSnapshot,
        event: ConnectionEvent.DirectConfirmed,
    ): ReduceResult {
        if (!state.recovery.permit.accepts(event.sessionEpoch, transportEpoch = event.transportEpoch)) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        val next = state.copy(
            activePath = VpnPath.Direct,
            transport = TransportLifecycle.Running,
            lastConfirmedPath = VpnPath.Direct,
            wifiFailStreak = 0,
            wifiStableHits = state.wifiStableHits + 1,
            directFailedOnNetwork = null,
            recovery = state.recovery.copy(
                phase = RecoveryPhase.Connected,
                failureIndex = 0,
                inFlight = false,
                nextRetryAtElapsedMs = null,
                permit = permit(state, state.sessionEpoch, netOps = true, inFlight = false),
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
        if (!state.recovery.permit.accepts(event.sessionEpoch, transportEpoch = event.transportEpoch)) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        val wifi = state.underlay.kind.prefersDirectInAuto()
        val next = state.copy(
            transport = TransportLifecycle.Failed,
            directFailedOnNetwork = event.networkKey ?: state.underlay.key,
            wifiFailStreak = if (wifi) state.wifiFailStreak + 1 else state.wifiFailStreak,
            wifiStableHits = if (wifi) 0 else state.wifiStableHits,
        )
        return decideNext(next, elapsedMs, jitterPermille)
    }

    private fun onBypassOk(
        state: ConnectionSnapshot,
        event: ConnectionEvent.BypassConfirmed,
    ): ReduceResult {
        if (!state.recovery.permit.accepts(event.sessionEpoch, transportEpoch = event.transportEpoch)) {
            return ReduceResult(state, RecoveryCommand.None)
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
                nextRetryAtElapsedMs = null,
                permit = permit(state, state.sessionEpoch, netOps = true, inFlight = false),
            ),
        )
        return ReduceResult(withUi(next, nowElapsedMs = 0L), RecoveryCommand.None)
    }

    private fun onBypassFailed(
        state: ConnectionSnapshot,
        event: ConnectionEvent.BypassFailed,
        elapsedMs: Long,
        jitterPermille: Int,
    ): ReduceResult {
        if (!state.recovery.permit.accepts(event.sessionEpoch, transportEpoch = event.transportEpoch)) {
            return ReduceResult(state, RecoveryCommand.None)
        }
        if (event.userAction != null) {
            val next = state.copy(
                transport = TransportLifecycle.Failed,
                recovery = state.recovery.copy(
                    phase = RecoveryPhase.NeedsUserAction,
                    inFlight = false,
                    permit = permit(state, state.sessionEpoch, netOps = false, inFlight = false),
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
        )
    }

    private fun decideNext(
        state: ConnectionSnapshot,
        elapsedMs: Long,
        jitterPermille: Int,
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
                directFailedOnNetwork = state.directFailedOnNetwork,
                lastConfirmedPath = state.lastConfirmedPath,
                wifiFailStreak = state.wifiFailStreak,
                wifiStableHits = state.wifiStableHits,
                parkedRawAlive = state.parkedRawAlive,
                elapsedMs = elapsedMs,
                profileId = state.intent.profileId,
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
                permit = permit(
                    state,
                    state.sessionEpoch,
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
            is AutoDecision.Stay -> ReduceResult(withUi(state, elapsedMs), RecoveryCommand.None)
            is AutoDecision.StartDirect -> {
                if (state.transport == TransportLifecycle.Failed &&
                    state.activePath == VpnPath.Direct
                ) {
                    val delay = RecoverySettings.retryDelayMs(state.recovery.failureIndex, jitterPermille)
                    return ReduceResult(
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
                val transportEpoch = state.transportEpoch + 1L
                val switching = state.activePath == VpnPath.Bypass &&
                    (state.transport == TransportLifecycle.Running || state.parkedRawAlive)
                val cmd = if (switching && state.parkedRawAlive) {
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
                                permit = permit(
                                    state.copy(transportEpoch = transportEpoch),
                                    state.sessionEpoch,
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
                if (state.transport == TransportLifecycle.Failed &&
                    state.activePath == VpnPath.Bypass
                ) {
                    val delay = RecoverySettings.retryDelayMs(state.recovery.failureIndex, jitterPermille)
                    return ReduceResult(
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
                val transportEpoch = state.transportEpoch + 1L
                val returning = decision.reason.contains("return-mobile") ||
                    (state.parkedRawAlive && state.activePath == VpnPath.Direct)
                val cmd = when {
                    state.parkedRawAlive -> RecoveryCommand.ResumeParkedRaw
                    state.call.validity == CallValidity.CredentialsExpired ->
                        RecoveryCommand.RefreshCredentials
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
                                permit = permit(
                                    state.copy(transportEpoch = transportEpoch),
                                    state.sessionEpoch,
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

    private fun permit(
        state: ConnectionSnapshot,
        sessionEpoch: Long,
        netOps: Boolean,
        inFlight: Boolean,
    ): RecoveryPermit = RecoveryPermit(
        sessionEpoch = sessionEpoch,
        networkEpoch = state.networkEpoch,
        transportEpoch = state.transportEpoch,
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
            ),
        )
    }
}
