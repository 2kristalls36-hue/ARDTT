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
    val provisionOk: Boolean,
    val message: String,
    val elapsedMs: Long,
)
