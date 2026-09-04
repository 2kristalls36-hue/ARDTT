package com.ardtt.app.core

/**
 * Auto on Wi‑Fi always uses Direct. Probe → Bypass stays for cellular (and
 * later). Manual Bypass still uses Path B on any underlay.
 */
fun autoUsesDirectOnWifi(mode: ConnPathMode, underlayKind: UnderlayKind): Boolean =
    mode == ConnPathMode.Auto && underlayKind == UnderlayKind.Wifi

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
 * Forced Bypass starts RAW without waiting on TCP :9100. Auto on cellular
 * always probes: open LTE (VPS up) must not skip into Bypass. Auto on Wi‑Fi
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
