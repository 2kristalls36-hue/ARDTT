package com.ardtt.app.core

/**
 * Auto on Wi‑Fi always uses Direct. Probe → Bypass stays for cellular (and
 * later). Manual Bypass still uses Path B on any underlay.
 */
fun autoUsesDirectOnWifi(mode: ConnPathMode, underlayKind: UnderlayKind): Boolean =
    mode == ConnPathMode.Auto && underlayKind == UnderlayKind.Wifi

/** Auto may start or fall back to Path B only on cellular (and unknown). */
fun autoMayUseBypass(mode: ConnPathMode, underlayKind: UnderlayKind, hasCallHash: Boolean): Boolean =
    mode == ConnPathMode.Auto && hasCallHash && !autoUsesDirectOnWifi(mode, underlayKind)

fun wifiAutoDirectProbe(elapsedMs: Long = 0): ProbeResult = ProbeResult(
    networkClass = NetworkClass.DirectOk,
    preselectedPath = VpnPath.Direct,
    systemOnline = true,
    yandexOk = true,
    bigtechOk = true,
    captive = false,
    awgUdpOk = true,
    provisionOk = true,
    message = "Авто на Wi‑Fi: прямое подключение",
    elapsedMs = elapsedMs,
)

/** Auto on Wi‑Fi never shows or follows a Bypass probe. */
fun displayedAutoProbe(
    mode: ConnPathMode,
    underlayKind: UnderlayKind,
    measured: ProbeResult,
): ProbeResult =
    if (autoUsesDirectOnWifi(mode, underlayKind)) wifiAutoDirectProbe(measured.elapsedMs) else measured

/**
 * Initial Connect path. Auto on Wi‑Fi is always Direct. On cellular (and
 * unknown underlay) Auto follows the probe: open internet + VPS :9100 → Direct;
 * Yandex-only whitelist → Bypass even if :9100 answers. Forced Direct/Bypass
 * ignore the probe.
 */
fun resolveConnectPath(
    mode: ConnPathMode,
    probePreferred: VpnPath?,
    lastGood: ProbeResult?,
    fresh: ProbeResult,
    underlayKind: UnderlayKind = UnderlayKind.Other,
    @Suppress("UNUSED_PARAMETER") bypassAllowed: Boolean = true,
): VpnPath? {
    when (mode) {
        ConnPathMode.Direct -> return VpnPath.Direct
        ConnPathMode.Bypass -> return VpnPath.Bypass
        ConnPathMode.Auto -> Unit
    }
    if (autoUsesDirectOnWifi(mode, underlayKind)) return VpnPath.Direct
    return fresh.preselectedPath ?: probePreferred.takeIf {
        lastGood?.networkClass == NetworkClass.DirectOk || lastGood?.preselectedPath != null
    }
}

/**
 * Forced Bypass starts RAW without waiting on VPS /health. Auto on cellular
 * always probes: open LTE (Cloudflare TLS + /health) must not skip into Bypass. Auto on Wi‑Fi
 * does not skip here either — [autoUsesDirectOnWifi] takes Direct without
 * using this Bypass-only shortcut.
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
