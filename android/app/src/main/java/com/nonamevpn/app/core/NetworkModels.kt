package com.nonamevpn.app.core

enum class NetworkClass {
    NoNetwork,
    Captive,
    DirectOk,
    NeedBypass,
    OpenNeedBypass,
}

enum class VpnPath {
    Direct,
    Bypass,
}

data class ProbeResult(
    val networkClass: NetworkClass,
    val preselectedPath: VpnPath?,
    val systemOnline: Boolean,
    /** UDP/TCP to 77.88.8.8:53 — underlay alive, including operator whitelist. */
    val yandexOk: Boolean,
    /** Unused in Auto (kept for older UI/tests). Path uses [provisionOk] as VPS IP. */
    val bigtechOk: Boolean,
    val captive: Boolean,
    /** AWG UDP handshake response from direct.endpoint (not used in live probe). */
    val awgUdpOk: Boolean,
    /** TCP to VPS IP:9100 — open internet / Direct target reachable. */
    val provisionOk: Boolean,
    val message: String,
    val elapsedMs: Long,
)
