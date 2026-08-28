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
    val yandexOk: Boolean,
    val bigtechOk: Boolean,
    val captive: Boolean,
    /** AWG UDP handshake response from direct.endpoint (real Direct viability). */
    val awgUdpOk: Boolean,
    /** TCP provision /health (host alive; not sufficient for Direct on whitelist). */
    val provisionOk: Boolean,
    val message: String,
    val elapsedMs: Long,
)
