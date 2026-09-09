package com.ardtt.app.core

/**
 * Auto Direct/Bypass table. Restriction hints never forbid a working Direct.
 * Unknown underlay is not treated as cellular.
 */
sealed class AutoDecision {
    data object WaitForUnderlay : AutoDecision()
    data object WaitSuspended : AutoDecision()
    data object WaitUnknownKind : AutoDecision()
    data object ShowCaptive : AutoDecision()
    data class StartDirect(
        val keepCall: Boolean,
        val immediate: Boolean,
        val reason: String,
    ) : AutoDecision()
    data class StartBypass(
        val reuseCall: Boolean,
        val reason: String,
    ) : AutoDecision()
    data class Stay(
        val path: VpnPath,
        val reason: String,
    ) : AutoDecision()
    data class ServerFault(val reason: String) : AutoDecision()
    data class Backoff(val reason: String) : AutoDecision()
    data class NeedsUserAction(val kind: UserActionKind, val reason: String) : AutoDecision()
}

data class AutoPathInput(
    val mode: ConnPathMode,
    val underlay: UnderlaySnapshot,
    val evidence: ReachabilityEvidence?,
    val currentPath: VpnPath?,
    val transport: TransportLifecycle,
    val hasCallHash: Boolean,
    val call: CallSessionState = CallSessionState(),
    val directNegative: DirectNegativeEvidence? = null,
    val lastConfirmedPath: VpnPath? = null,
    val wifiFailStreak: Int = 0,
    val wifiStableHits: Int = 0,
    val parkedRawAlive: Boolean = false,
    val elapsedMs: Long = 0L,
    val profileId: String? = null,
)

fun UnderlayKind.prefersDirectInAuto(): Boolean =
    this == UnderlayKind.Wifi || this == UnderlayKind.Ethernet

fun decideAutoPath(input: AutoPathInput): AutoDecision {
    val underlay = input.underlay
    when (underlay.availability) {
        UnderlayAvailability.None -> return AutoDecision.WaitForUnderlay
        UnderlayAvailability.Incomplete -> return AutoDecision.WaitUnknownKind
        UnderlayAvailability.Suspended -> {
            if (!underlay.wifiConnected && !underlay.ethernetConnected) {
                return AutoDecision.WaitSuspended
            }
        }
        UnderlayAvailability.Captive -> {
            if (underlay.kind == UnderlayKind.Wifi && underlay.cellularConnected) {
                return if (input.currentPath == VpnPath.Bypass &&
                    input.transport == TransportLifecycle.Running
                ) {
                    AutoDecision.Stay(VpnPath.Bypass, "captive-wifi-keep-mobile")
                } else if (input.hasCallHash) {
                    AutoDecision.StartBypass(reuseCall = true, reason = "captive-wifi-keep-mobile")
                } else {
                    AutoDecision.ShowCaptive
                }
            }
            if (underlay.kind == UnderlayKind.Wifi) {
                return AutoDecision.ShowCaptive
            }
        }
        UnderlayAvailability.Usable -> Unit
    }

    if (input.call.validity == CallValidity.NeedsAuth &&
        input.mode == ConnPathMode.Bypass
    ) {
        return AutoDecision.NeedsUserAction(UserActionKind.SignIn, "call-auth")
    }
    if (input.call.validity == CallValidity.ConfirmedDead &&
        input.mode == ConnPathMode.Bypass
    ) {
        return AutoDecision.NeedsUserAction(UserActionKind.CallDead, "call-dead")
    }

    when (input.mode) {
        ConnPathMode.Direct -> return manualPath(VpnPath.Direct, input)
        ConnPathMode.Bypass -> return if (input.hasCallHash) {
            manualPath(VpnPath.Bypass, input)
        } else {
            AutoDecision.NeedsUserAction(UserActionKind.SignIn, "bypass-no-hash")
        }
        ConnPathMode.Auto -> Unit
    }

    if (underlay.kind == UnderlayKind.Other) {
        return AutoDecision.WaitUnknownKind
    }

    val wifiUsable = underlay.kind.prefersDirectInAuto() &&
        underlay.availability == UnderlayAvailability.Usable
    if (wifiUsable) {
        val hysteresis = input.wifiFailStreak >=
            RecoverySettings.WIFI_DEGRADED_FAILS_BEFORE_HYSTERESIS &&
            input.wifiStableHits < RecoverySettings.WIFI_STABLE_CONFIRMATIONS &&
            input.currentPath == VpnPath.Bypass &&
            input.transport == TransportLifecycle.Running
        if (hysteresis) {
            return AutoDecision.Stay(VpnPath.Bypass, "wifi-hysteresis")
        }
        if (input.wifiFailStreak >= RecoverySettings.WIFI_DEGRADED_FAILS_BEFORE_HYSTERESIS &&
            input.underlay.cellularConnected &&
            (input.parkedRawAlive || input.hasCallHash) &&
            input.transport != TransportLifecycle.Running &&
            input.transport != TransportLifecycle.Starting
        ) {
            return AutoDecision.StartBypass(
                reuseCall = true,
                reason = "wifi-failed-return-mobile",
            )
        }
        val keepCall = input.call.canReuse || input.hasCallHash
        if (input.currentPath == VpnPath.Direct &&
            (input.transport == TransportLifecycle.Running ||
                input.transport == TransportLifecycle.Starting)
        ) {
            return AutoDecision.Stay(VpnPath.Direct, "wifi-direct-running")
        }
        return AutoDecision.StartDirect(
            keepCall = keepCall,
            immediate = input.wifiFailStreak == 0,
            reason = "wifi-direct",
        )
    }

    if (underlay.kind != UnderlayKind.Cellular) {
        return AutoDecision.StartDirect(
            keepCall = input.hasCallHash,
            immediate = false,
            reason = "other-direct",
        )
    }

    if (input.parkedRawAlive &&
        input.hasCallHash &&
        input.wifiFailStreak > 0
    ) {
        if (input.currentPath == VpnPath.Bypass &&
            input.transport == TransportLifecycle.Running
        ) {
            return AutoDecision.Stay(VpnPath.Bypass, "wifi-failed-keep-mobile")
        }
        return AutoDecision.StartBypass(
            reuseCall = true,
            reason = "wifi-failed-return-mobile",
        )
    }

    val blocked = input.directNegative?.stillBlocks(
        input.elapsedMs,
        underlay.key,
        input.profileId,
    ) == true
    val restriction = input.evidence?.restriction ?: RestrictionHint.Unknown
    val internetOk = input.evidence?.yandex?.isSuccess == true ||
        input.evidence?.bigtech?.isSuccess == true
    val vpsRoutingBroken = internetOk &&
        input.evidence?.provision?.isFailure == true &&
        blocked

    if (input.currentPath == VpnPath.Direct &&
        (input.transport == TransportLifecycle.Running ||
            input.transport == TransportLifecycle.Starting) &&
        !blocked
    ) {
        return AutoDecision.Stay(VpnPath.Direct, "direct-works")
    }

    if (input.currentPath == VpnPath.Bypass &&
        (input.transport == TransportLifecycle.Running ||
            input.transport == TransportLifecycle.Starting)
    ) {
        return AutoDecision.Stay(VpnPath.Bypass, "bypass-running")
    }

    if (vpsRoutingBroken && !input.hasCallHash) {
        return AutoDecision.ServerFault("vps-unreachable")
    }

    if (!blocked) {
        if (input.currentPath == VpnPath.Direct &&
            input.transport == TransportLifecycle.Starting
        ) {
            return AutoDecision.Stay(VpnPath.Direct, "direct-try-in-flight")
        }
        return AutoDecision.StartDirect(
            keepCall = input.hasCallHash,
            immediate = false,
            reason = if (restriction == RestrictionHint.Suspected ||
                restriction == RestrictionHint.Confirmed
            ) {
                "cellular-try-direct"
            } else {
                "cellular-direct"
            },
        )
    }

    if (input.hasCallHash && input.call.canReuse) {
        return AutoDecision.StartBypass(reuseCall = true, reason = "cellular-direct-failed")
    }

    if (vpsRoutingBroken) {
        return AutoDecision.ServerFault("vps-unreachable")
    }
    return AutoDecision.Backoff("all-paths-failed")
}

private fun manualPath(path: VpnPath, input: AutoPathInput): AutoDecision {
    if (input.currentPath == path &&
        (input.transport == TransportLifecycle.Running ||
            input.transport == TransportLifecycle.Starting)
    ) {
        return AutoDecision.Stay(path, "manual-running")
    }
    return when (path) {
        VpnPath.Direct -> AutoDecision.StartDirect(
            keepCall = input.hasCallHash,
            immediate = input.underlay.kind.prefersDirectInAuto(),
            reason = "manual-direct",
        )
        VpnPath.Bypass -> AutoDecision.StartBypass(
            reuseCall = true,
            reason = "manual-bypass",
        )
    }
}

fun AutoDecision.toHandoverDecision(currentPath: VpnPath): NetworkHandoverDecision = when (this) {
    AutoDecision.WaitForUnderlay,
    AutoDecision.WaitSuspended,
    AutoDecision.WaitUnknownKind,
    -> NetworkHandoverDecision.HoldWaitForNetwork
    AutoDecision.ShowCaptive -> NetworkHandoverDecision.HoldWaitForNetwork
    is AutoDecision.NeedsUserAction -> NetworkHandoverDecision.HoldWaitForNetwork
    is AutoDecision.ServerFault -> NetworkHandoverDecision.HoldWaitForNetwork
    is AutoDecision.Backoff -> NetworkHandoverDecision.HoldWaitForNetwork
    is AutoDecision.Stay -> {
        if (path == currentPath) {
            NetworkHandoverDecision.NoAction
        } else {
            NetworkHandoverDecision.SwitchPath(path)
        }
    }
    is AutoDecision.StartDirect -> {
        if (currentPath == VpnPath.Direct) {
            if (immediate) {
                NetworkHandoverDecision.SoftRestartSamePath
            } else {
                NetworkHandoverDecision.NoAction
            }
        } else {
            NetworkHandoverDecision.SwitchPath(VpnPath.Direct)
        }
    }
    is AutoDecision.StartBypass -> {
        if (currentPath == VpnPath.Bypass) {
            NetworkHandoverDecision.SoftRestartSamePath
        } else {
            NetworkHandoverDecision.SwitchPath(VpnPath.Bypass)
        }
    }
}

fun cacheAllowsProbeSkip(
    cache: ReachabilityEvidence?,
    key: NetworkKey?,
    profileId: String?,
    elapsedMs: Long,
    availability: UnderlayAvailability,
    userStop: Boolean,
): Boolean {
    if (userStop) return false
    if (availability == UnderlayAvailability.None ||
        availability == UnderlayAvailability.Suspended ||
        availability == UnderlayAvailability.Captive
    ) {
        return false
    }
    return cache?.usableAt(elapsedMs, key, profileId) == true
}
