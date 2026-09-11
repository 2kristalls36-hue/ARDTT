package com.ardtt.app.core

/**
 * Auto on Wi‑Fi / Ethernet always uses Direct. Cellular Auto uses Bypass
 * when the whitelist score is at enter threshold. Unknown underlay is not mobile.
 */
fun autoUsesDirectOnWifi(mode: ConnPathMode, underlayKind: UnderlayKind): Boolean =
    mode == ConnPathMode.Auto && underlayKind.prefersDirectInAuto()

/** Auto may start or fall back to Path B only on cellular. */
fun autoMayUseBypass(mode: ConnPathMode, underlayKind: UnderlayKind, hasCallHash: Boolean): Boolean =
    mode == ConnPathMode.Auto && hasCallHash && underlayKind == UnderlayKind.Cellular

fun wifiAutoDirectProbe(elapsedMs: Long = 0): ProbeResult = ProbeResult(
    networkClass = NetworkClass.DirectOk,
    preselectedPath = VpnPath.Direct,
    systemOnline = true,
    yandexOk = false,
    bigtechOk = false,
    captive = false,
    awgUdpOk = false,
    provisionOk = false,
    message = "Авто на Wi‑Fi: прямое подключение",
    elapsedMs = elapsedMs,
        yandexOutcome = CheckOutcome.NotRun,
        bigtechOutcome = CheckOutcome.NotRun,
        googleOutcome = CheckOutcome.NotRun,
        provisionOutcome = CheckOutcome.NotRun,
        restriction = RestrictionHint.None,
        whitelistScorePercent = 0,
        routeReason = "direct",
)

/** Auto on Wi‑Fi never follows a Bypass probe; measured outcomes are kept. */
fun displayedAutoProbe(
    mode: ConnPathMode,
    underlayKind: UnderlayKind,
    measured: ProbeResult,
): ProbeResult =
    if (autoUsesDirectOnWifi(mode, underlayKind)) {
        measured.copy(
            networkClass = NetworkClass.DirectOk,
            preselectedPath = VpnPath.Direct,
            message = "Авто на Wi‑Fi: прямое подключение",
            restriction = RestrictionHint.None,
            whitelistScorePercent = 0,
        )
    } else {
        measured
    }

/**
 * Initial Connect path. Auto on Wi‑Fi/Ethernet is Direct. On cellular Auto
 * uses Bypass when the whitelist score is at enter threshold (or Direct
 * already failed on this underlay). NoNetwork and Captive do not reuse a
 * previous successful path.
 */
fun resolveConnectPath(
    mode: ConnPathMode,
    @Suppress("UNUSED_PARAMETER") probePreferred: VpnPath?,
    @Suppress("UNUSED_PARAMETER") lastGood: ProbeResult?,
    fresh: ProbeResult,
    underlayKind: UnderlayKind = UnderlayKind.Other,
    bypassAllowed: Boolean = true,
    directFailedOnCurrentUnderlay: Boolean = false,
    underlayUsable: Boolean = false,
    whitelistScorePercent: Int = 0,
): VpnPath? {
    when (mode) {
        ConnPathMode.Direct -> return VpnPath.Direct
        ConnPathMode.Bypass -> return VpnPath.Bypass
        ConnPathMode.Auto -> Unit
    }
    if (autoUsesDirectOnWifi(mode, underlayKind)) return VpnPath.Direct
    if (underlayKind == UnderlayKind.Other) return null
    if (fresh.captive || fresh.networkClass == NetworkClass.Captive) return null
    if (fresh.networkClass == NetworkClass.NoNetwork) {
        return if (underlayUsable) VpnPath.Direct else null
    }
    if (fresh.networkClass == NetworkClass.DataUnconfirmed) return VpnPath.Direct
    if (directFailedOnCurrentUnderlay && bypassAllowed) return VpnPath.Bypass
    val score = maxOf(whitelistScorePercent, fresh.whitelistScorePercent)
    if (bypassAllowed && RestrictionScore.likely(score, alreadyBypass = false)) {
        return VpnPath.Bypass
    }
    return VpnPath.Direct
}

/**
 * Forced Bypass starts RAW without waiting on VPS /health. Auto always
 * probes: a working Direct must not be skipped.
 */
fun shouldSkipConnectProbe(
    pathMode: ConnPathMode,
    bypassAllowed: Boolean,
    @Suppress("UNUSED_PARAMETER") underlayKind: UnderlayKind,
): Boolean = when (pathMode) {
    ConnPathMode.Direct -> false
    ConnPathMode.Bypass -> bypassAllowed
    ConnPathMode.Auto -> false
}

/** Usable physical underlay: start Direct without waiting for public/provision probes. */
fun shouldStartDirectWithoutDiagnostic(
    mode: ConnPathMode,
    underlayKind: UnderlayKind,
    underlayUsable: Boolean,
): Boolean {
    if (!underlayUsable) return false
    return when (mode) {
        ConnPathMode.Direct -> true
        ConnPathMode.Auto ->
            underlayKind == UnderlayKind.Cellular || underlayKind.prefersDirectInAuto()
        ConnPathMode.Bypass -> false
    }
}

fun connectSnapshotChanged(
    capturedMode: ConnPathMode,
    liveMode: ConnPathMode,
    capturedKind: UnderlayKind,
    liveKind: UnderlayKind,
    capturedProfileId: String?,
    liveProfileId: String?,
): Boolean = capturedMode != liveMode ||
    capturedKind != liveKind ||
    capturedProfileId != liveProfileId

/** Widget / shortcut: Auto cellular waits for a whitelist probe; Wi‑Fi Direct does not. */
internal fun connectNeedsInitialProbe(
    mode: ConnPathMode,
    probePreferred: VpnPath?,
    underlayKind: UnderlayKind,
    bypassAllowed: Boolean,
    underlayUsable: Boolean = false,
): Boolean {
    if (mode != ConnPathMode.Auto || probePreferred != null) return false
    if (underlayKind == UnderlayKind.Cellular) return true
    if (shouldStartDirectWithoutDiagnostic(mode, underlayKind, underlayUsable)) return false
    return !autoUsesDirectOnWifi(mode, underlayKind) &&
        !shouldSkipConnectProbe(mode, bypassAllowed, underlayKind)
}

/**
 * Auto cellular Connect must finish (or reuse) a probe so БС is known before
 * the first Direct attempt. Handover still uses
 * [shouldStartDirectWithoutDiagnostic] to skip a blocking round-trip.
 */
fun shouldWaitForCellularWhitelistProbe(
    mode: ConnPathMode,
    underlayKind: UnderlayKind,
    state: ConnState,
    hasSameNetworkProbeEvidence: Boolean,
): Boolean {
    if (mode != ConnPathMode.Auto || underlayKind != UnderlayKind.Cellular) return false
    if (state == ConnState.Probing) return true
    if (state != ConnState.Idle &&
        state != ConnState.Ready &&
        state != ConnState.Error
    ) {
        return false
    }
    return !hasSameNetworkProbeEvidence
}
