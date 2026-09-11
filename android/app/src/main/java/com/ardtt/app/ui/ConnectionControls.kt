package com.ardtt.app.ui

import com.ardtt.app.bypass.DialPath
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.BypassWorkers
import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.ConnectionUiAction
import com.ardtt.app.core.blocksProfileSwitch
import com.ardtt.app.core.disconnectsOnPowerClick
import com.ardtt.app.core.holdsUserSession
import com.ardtt.app.settings.AppSettingsRepository

/** Path / Hide-IP chips stay locked during a session unless the user unlocked them. */
internal fun connectionControlsLocked(
    sessionActive: Boolean,
    unlockWhileConnected: Boolean,
): Boolean = sessionActive && !unlockWhileConnected

/** Live VPN session — do not switch the active profile / user. */
internal fun vpnSessionBlocksProfileSwitch(state: ConnState): Boolean = state.blocksProfileSwitch()

internal fun tunnelStickyCtaLabel(state: ConnState): String = when (state) {
    ConnState.Connecting -> "Отменить"
    ConnState.WaitingForNetwork -> "Отменить ожидание"
    ConnState.Recovering, ConnState.CaptivePortal, ConnState.NeedsUserAction -> "Остановить"
    ConnState.Connected, ConnState.PausedTrustedWifi -> "Остановить"
    else -> "Подключить"
}

internal fun tunnelStickyCtaIsDestructive(state: ConnState): Boolean = state.disconnectsOnPowerClick() &&
    state != ConnState.Disconnecting

internal fun tunnelStickyCtaEnabled(state: ConnState, connectEnabled: Boolean): Boolean = when (state) {
    ConnState.Connecting,
    ConnState.Connected,
    ConnState.PausedTrustedWifi,
    ConnState.WaitingForNetwork,
    ConnState.Recovering,
    ConnState.CaptivePortal,
    ConnState.NeedsUserAction,
    -> true
    ConnState.Disconnecting -> false
    ConnState.Probing -> true
    else -> connectEnabled
}

internal fun tunnelPowerBusy(state: ConnState): Boolean =
    state == ConnState.Connecting || state == ConnState.Disconnecting

internal fun tunnelPowerClickEnabled(state: ConnState, connectEnabled: Boolean): Boolean =
    tunnelPowerToggleEnabled(state, connectEnabled)

internal fun tunnelPowerSessionLit(state: ConnState): Boolean = state.holdsUserSession()

internal fun tunnelPowerToggleEnabled(state: ConnState, connectEnabled: Boolean): Boolean = when (state) {
    ConnState.Connecting,
    ConnState.WaitingForNetwork,
    ConnState.Recovering,
    ConnState.CaptivePortal,
    ConnState.NeedsUserAction,
    -> true
    ConnState.Disconnecting -> false
    ConnState.Probing -> connectEnabled
    ConnState.Connected, ConnState.PausedTrustedWifi -> true
    else -> connectEnabled
}

internal fun tunnelPowerClickDisconnects(state: ConnState): Boolean = state.disconnectsOnPowerClick()

internal fun tunnelPowerContentDescription(state: ConnState, connected: Boolean): String = when (state) {
    ConnState.Connecting -> "Отменить подключение"
    ConnState.WaitingForNetwork -> "Отменить ожидание сети"
    ConnState.Disconnecting -> "Остановка"
    ConnState.PausedTrustedWifi -> "Остановить паузу Wi‑Fi"
    else -> if (connected || state.holdsUserSession()) "Остановить туннель" else "Подключить туннель"
}

/**
 * Sticky CTA / power control already stop the session — do not also show
 * [ConnectionUiAction.Disconnect] as a chip under the status panel.
 */
internal fun tunnelChromeActions(actions: List<ConnectionUiAction>): List<ConnectionUiAction> =
    actions.filterNot { it == ConnectionUiAction.Disconnect }

internal fun tunnelProfileCenterOpensImport(count: Int): Boolean = count <= 0

internal fun tunnelProfileCenterOpensManage(count: Int): Boolean = count >= 1

internal const val PROFILE_SWITCH_LOCKED_MESSAGE =
    "Отключите туннель, чтобы сменить профиль."

internal fun pathModeNeedsCallHash(mode: ConnPathMode, hasCallHash: Boolean): Boolean =
    mode == ConnPathMode.Bypass && !hasCallHash

/** Push Settings → ConnectionManager without duplicating the mapping in every screen. */
internal fun applySessionConnectionPrefs(
    conn: ConnectionManager,
    silentRecreate: Boolean,
    dialSetting: String,
    pathModeSetting: String,
    hideIp: Boolean,
) {
    conn.setSilentRecreate(silentRecreate)
    conn.setWorkers(BypassWorkers.DEFAULT)
    conn.setDialPath(DialPath.fromSetting(dialSetting))
    conn.setPathMode(ConnPathMode.fromSetting(pathModeSetting), switchLive = false)
    conn.setHideIp(hideIp)
}

private suspend fun persistPathMode(settings: AppSettingsRepository, mode: ConnPathMode) {
    val name = ConnPathMode.toSetting(mode)
    settings.setPathMode(name)
    AppLog.i("PathMode", name)
}

/** Settings and Tunnel path chips share this write path. */
internal suspend fun commitPathMode(
    settings: AppSettingsRepository,
    conn: ConnectionManager,
    mode: ConnPathMode,
) {
    persistPathMode(settings, mode)
    conn.setPathMode(mode, switchLive = true)
}

private suspend fun persistHideIp(settings: AppSettingsRepository, enabled: Boolean) {
    settings.setHideIp(enabled)
    AppLog.i("HideIP", if (enabled) "enabled (hidden address)" else "disabled (server address)")
}

/** Settings and Tunnel Hide-IP chips share this write path. */
internal suspend fun commitHideIp(
    settings: AppSettingsRepository,
    conn: ConnectionManager,
    enabled: Boolean,
) {
    persistHideIp(settings, enabled)
    conn.setHideIp(enabled)
}

internal suspend fun persistDialPath(settings: AppSettingsRepository, path: DialPath) {
    settings.setDialPath(path.toSetting())
}

internal suspend fun persistSilentRecreate(settings: AppSettingsRepository, enabled: Boolean) {
    settings.setSilentRecreate(enabled)
}

internal suspend fun persistThemeMode(settings: AppSettingsRepository, mode: String) {
    settings.setThemeMode(mode)
}

internal fun themeModeIsDark(themeMode: String, systemDark: Boolean): Boolean =
    when (AppSettingsRepository.normalizeThemeMode(themeMode)) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

internal fun nextThemeMode(current: String): String =
    when (AppSettingsRepository.normalizeThemeMode(current)) {
        "system" -> "light"
        "light" -> "dark"
        else -> "system"
    }

internal fun themeModeVisualKey(current: String): String =
    when (AppSettingsRepository.normalizeThemeMode(current)) {
        "light" -> "light"
        "dark" -> "dark"
        else -> "auto"
    }

/** Tunnel «Параметры подключения» card — hidden when the Settings toggle is on. */
internal fun tunnelConnectionParamsVisible(hideQuickSettings: Boolean): Boolean = !hideQuickSettings

internal fun tunnelQuickSettingsProfileHelp(count: Int, locked: Boolean): String? = when {
    count <= 0 -> "Нет сохранённых профилей."
    locked -> PROFILE_SWITCH_LOCKED_MESSAGE
    else -> null
}

internal fun latestAppVersionCode(installedCode: Int, catalogCode: Int): Int =
    maxOf(installedCode.coerceAtLeast(0), catalogCode.coerceAtLeast(0))
