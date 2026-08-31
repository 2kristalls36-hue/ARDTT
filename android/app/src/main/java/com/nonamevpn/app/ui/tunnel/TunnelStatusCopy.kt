package com.nonamevpn.app.ui.tunnel

import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.VpnPath

fun sessionCardStatusText(
    state: ConnState,
    publicIp: String?,
    lastError: String? = null,
): String {
    return when (state) {
        // Tunnel is up. Public IP may never arrive on operator whitelist
        // (provision/ipify over underlay time out) — do not look stuck.
        ConnState.Connected -> "Подключено"
        ConnState.Connecting, ConnState.Probing -> "Подключение…"
        ConnState.Disconnecting -> "Отключение…"
        ConnState.PausedTrustedWifi -> "Пауза"
        ConnState.Error -> {
            val err = lastError?.trim().orEmpty()
            if (err.isNotEmpty()) "Ошибка: ${err.take(80)}" else "Ошибка"
        }
        ConnState.Idle, ConnState.Ready -> "Отключено"
    }
}

fun selectedModeLabel(pathMode: String): String = when (pathMode) {
    "direct" -> "Прямой"
    "bypass" -> "Обход"
    else -> "Авто"
}

fun currentModeLabel(
    state: ConnState,
    activePath: VpnPath?,
): String {
    val live = state == ConnState.Connected ||
        state == ConnState.Connecting ||
        state == ConnState.PausedTrustedWifi
    if (!live) return "—"
    return when (activePath) {
        VpnPath.Direct -> "Прямой"
        VpnPath.Bypass -> "Обход"
        null -> "—"
    }
}
