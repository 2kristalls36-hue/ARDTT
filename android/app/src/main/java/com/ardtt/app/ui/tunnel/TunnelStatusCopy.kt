package com.ardtt.app.ui.tunnel

import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.VpnPath
import com.ardtt.app.core.holdsUserSession
import com.ardtt.app.ui.HideIpCopy

/** Primary status line under the connect button in user mode. */
fun userModeStatusPrimary(
    state: ConnState,
    activePath: VpnPath?,
): String = when (state) {
    ConnState.Connected -> when (activePath) {
        VpnPath.Direct -> "Прямое подключение"
        VpnPath.Bypass -> "Обход"
        null -> "Подключено"
    }
    ConnState.PausedTrustedWifi -> "Пауза"
    ConnState.WaitingForNetwork -> "Нет подключения. Включите Wi‑Fi или мобильный интернет"
    ConnState.Recovering -> "Восстанавливаем соединение"
    ConnState.CaptivePortal -> "Войдите в сеть Wi‑Fi"
    ConnState.NeedsUserAction -> "Требуется действие"
    ConnState.Probing -> "Проверка сети…"
    ConnState.Connecting -> when (activePath) {
        VpnPath.Bypass -> "Подключение через обход…"
        VpnPath.Direct -> "Подключение…"
        null -> "Подключение…"
    }
    ConnState.Disconnecting -> "Отключение…"
    ConnState.Error -> "Ошибка подключения"
    ConnState.Idle, ConnState.Ready -> "Отключено"
}

/** Secondary hint under the connect button in user mode. */
/**
 * Bypass is wanted (or the status text talks about it) but no call hash is
 * stored and no session is up — the user needs the «Код звонка» card.
 */
fun userModeNeedsCallHashHint(
    state: ConnState,
    hasCallHash: Boolean,
    activePath: VpnPath?,
    details: String?,
): Boolean {
    if (hasCallHash) return false
    if (state == ConnState.Connected || state == ConnState.PausedTrustedWifi) return false
    return activePath == VpnPath.Bypass ||
        details?.contains("обход", ignoreCase = true) == true ||
        details?.contains("звонка", ignoreCase = true) == true ||
        details?.contains("hash", ignoreCase = true) == true
}

fun userModeStatusDetails(
    state: ConnState,
    softInfo: String?,
    lastError: String?,
    activePath: VpnPath? = null,
    hideIp: Boolean = false,
    appsWhitelist: Boolean = false,
    whitelistAppCount: Int = 0,
): String? = when (state) {
    ConnState.Connected -> userModeConnectedDetails(hideIp, appsWhitelist, whitelistAppCount)
    ConnState.PausedTrustedWifi -> userModePausedDetails(activePath, softInfo)
    ConnState.Disconnecting -> null
    ConnState.WaitingForNetwork -> "Ожидаем Wi‑Fi или мобильный интернет."
    ConnState.Recovering -> userModeSoftInfo(softInfo) ?: "Следующая попытка скоро."
    ConnState.CaptivePortal -> "Сеть требует входа через браузер."
    ConnState.NeedsUserAction -> lastError?.trim()?.take(140)?.takeIf { it.isNotEmpty() }
    ConnState.Probing -> "Подготавливаем соединение…"
    ConnState.Connecting -> userModeSoftInfo(softInfo) ?: "Устанавливаем защищённое соединение…"
    ConnState.Error -> lastError?.trim()?.take(140)?.takeIf { it.isNotEmpty() }
    ConnState.Idle, ConnState.Ready -> userModeSoftInfo(softInfo)
}

/**
 * Direct+БС (apps whitelist) leaves every other package on the operator path.
 * Ticket 24: tunnel showed «Прямое подключение» with 20 allowed apps and no
 * hint, while the user reported «нет интернета».
 */
fun userModeWhitelistHint(appsWhitelist: Boolean, appCount: Int): String? {
    if (!appsWhitelist || appCount <= 0) return null
    val scope = if (appCount == 1) {
        "Только 1 приложение через туннель"
    } else {
        "Только $appCount приложений через туннель"
    }
    return "$scope. Остальные — мимо VPN."
}

private fun userModeConnectedDetails(
    hideIp: Boolean,
    appsWhitelist: Boolean,
    whitelistAppCount: Int,
): String? {
    val whitelist = userModeWhitelistHint(appsWhitelist, whitelistAppCount)
    val incognito = if (hideIp) HideIpCopy.STATUS_HIDDEN else null
    return listOfNotNull(whitelist, incognito).joinToString(" · ").ifBlank { null }
}

private fun userModePausedDetails(activePath: VpnPath?, softInfo: String?): String? {
    val pathLabel = when (activePath) {
        VpnPath.Direct -> "Прямое"
        VpnPath.Bypass -> "Обход"
        null -> null
    }
    val hint = softInfo?.trim()?.takeIf { it.isNotBlank() }
    return when {
        pathLabel != null && hint != null -> "$pathLabel · $hint"
        hint != null -> hint
        pathLabel != null -> "Доверенная сеть Wi‑Fi · $pathLabel"
        else -> "Доверенная сеть Wi‑Fi"
    }
}

private fun userModeSoftInfo(softInfo: String?): String? {
    val raw = softInfo?.trim().orEmpty()
    if (raw.isBlank()) return null
    return when {
        raw.contains("код звонка", ignoreCase = true) ||
            raw.contains("hash", ignoreCase = true) ->
            "Для обхода добавьте код звонка в настройках."
        raw.contains("ВКонтакте", ignoreCase = true) ||
            raw.contains("VK", ignoreCase = true) ->
            "Требуется вход в ВКонтакте для создания кода звонка."
        raw.contains("captive", ignoreCase = true) ||
            raw.contains("авторизац", ignoreCase = true) ->
            "Сеть требует входа через браузер. Выполните авторизацию Wi‑Fi."
        raw.contains("документационн", ignoreCase = true) ||
            raw.contains("VPS", ignoreCase = true) ||
            raw.contains("белый список", ignoreCase = true) ||
            raw.contains("подтверждены проверками", ignoreCase = true) ->
            "Прямое подключение недоступно. Подключаем обход."
        raw.contains("устарели", ignoreCase = true) ->
            "Данные о сети устарели. Проверяем снова."
        raw.contains("Возможны ограничения", ignoreCase = true) ->
            "Возможны ограничения мобильной сети."
        raw.contains("Исходящий адрес скрыт", ignoreCase = true) ->
            "Используется режим «Инкогнито»."
        raw.contains("доверенн", ignoreCase = true) ||
            raw.contains("восстанов", ignoreCase = true) ||
            raw.contains("приостанов", ignoreCase = true) ||
            raw.contains("Wi‑Fi", ignoreCase = true) ||
            raw.contains("Wi-Fi", ignoreCase = true) ->
            raw
        raw.contains("обход", ignoreCase = true) ->
            "Сеть с ограничениями. Подключаем обход."
        else -> null
    }
}

fun sessionCardStatusText(
    state: ConnState,
    publicIp: String?,
    lastError: String? = null,
): String {
    return when (state) {
        // Tunnel is up. Public IP may never arrive on operator whitelist
        // (provision/ipify over underlay time out) — do not look stuck.
        ConnState.Connected -> "Подключено"
        ConnState.Connecting -> "Подключение…"
        ConnState.Probing -> "Проверка сети…"
        ConnState.WaitingForNetwork -> "Нет сети"
        ConnState.Recovering -> "Восстановление…"
        ConnState.CaptivePortal -> "Вход в сеть"
        ConnState.NeedsUserAction -> "Нужно действие"
        ConnState.Disconnecting -> "Отключение…"
        ConnState.PausedTrustedWifi -> "Пауза"
        ConnState.Error -> {
            val err = lastError?.trim().orEmpty()
            if (err.isNotEmpty()) "Ошибка: ${err.take(80)}" else "Ошибка"
        }
        ConnState.Idle, ConnState.Ready -> "Отключено"
    }
}

fun selectedModeLabel(pathMode: String): String = when (ConnPathMode.fromSetting(pathMode)) {
    ConnPathMode.Direct -> "Прямой"
    ConnPathMode.Bypass -> "Обход"
    ConnPathMode.Auto -> "Авто"
}

fun currentModeLabel(
    state: ConnState,
    activePath: VpnPath?,
): String {
    val live = state.holdsUserSession()
    if (!live) return "—"
    return when (activePath) {
        VpnPath.Direct -> "Прямой"
        VpnPath.Bypass -> "Обход"
        null -> "—"
    }
}

internal object TunnelPanelCopy {
    const val SIGNAL_LABEL = "Уровень сигнала"
}

internal enum class StatusSignalPlacement {
    LabelStart,
    LabelEnd,
}

internal fun statusSignalPlacement(): StatusSignalPlacement = StatusSignalPlacement.LabelStart

internal enum class SignalMetricMark {
    Plain,
    Underline,
}

/** The live underlay gets a line under its reading. The other level stays plain. */
internal fun signalMetricMark(emphasized: Boolean): SignalMetricMark =
    if (emphasized) SignalMetricMark.Underline else SignalMetricMark.Plain
