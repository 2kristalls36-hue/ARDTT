package com.ardtt.app.core

/**
 * Auto on Wi‑Fi / Ethernet always uses Direct. Probe → Bypass stays for
 * confirmed cellular after Direct failed. Unknown underlay is not mobile.
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
        )
    } else {
        measured
    }

/**
 * Initial Connect path. Auto on Wi‑Fi/Ethernet is Direct. On cellular Auto
 * tries Direct unless Direct already failed on this underlay. NoNetwork and
 * Captive do not reuse a previous successful path.
 */
fun resolveConnectPath(
    mode: ConnPathMode,
    @Suppress("UNUSED_PARAMETER") probePreferred: VpnPath?,
    @Suppress("UNUSED_PARAMETER") lastGood: ProbeResult?,
    fresh: ProbeResult,
    underlayKind: UnderlayKind = UnderlayKind.Other,
    bypassAllowed: Boolean = true,
    directFailedOnCurrentUnderlay: Boolean = false,
): VpnPath? {
    when (mode) {
        ConnPathMode.Direct -> return VpnPath.Direct
        ConnPathMode.Bypass -> return VpnPath.Bypass
        ConnPathMode.Auto -> Unit
    }
    if (autoUsesDirectOnWifi(mode, underlayKind)) return VpnPath.Direct
    if (underlayKind == UnderlayKind.Other) return null
    if (fresh.networkClass == NetworkClass.NoNetwork || fresh.captive) {
        return null
    }
    if (fresh.networkClass == NetworkClass.Captive) return null
    if (directFailedOnCurrentUnderlay && bypassAllowed) return VpnPath.Bypass
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

/** Widget / shortcut: Auto on cellular needs a probe before [ConnectionManager.connect]. */
internal fun connectNeedsInitialProbe(
    mode: ConnPathMode,
    probePreferred: VpnPath?,
    underlayKind: UnderlayKind,
    bypassAllowed: Boolean,
): Boolean {
    if (mode != ConnPathMode.Auto || probePreferred != null) return false
    return !autoUsesDirectOnWifi(mode, underlayKind) &&
        !shouldSkipConnectProbe(mode, bypassAllowed, underlayKind)
}
