package com.nonamevpn.app.ui

import com.nonamevpn.app.bypass.DialPath
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.BypassWorkers
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.settings.AppSettingsRepository

/** Path / Hide-IP chips stay locked during a session unless the user unlocked them. */
internal fun connectionControlsLocked(
    sessionActive: Boolean,
    unlockWhileConnected: Boolean,
): Boolean = sessionActive && !unlockWhileConnected

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

internal suspend fun persistPathMode(settings: AppSettingsRepository, mode: ConnPathMode) {
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

internal suspend fun persistHideIp(settings: AppSettingsRepository, enabled: Boolean) {
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

internal suspend fun persistThemeMode(settings: AppSettingsRepository, mode: String) {
    settings.setThemeMode(mode)
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

internal fun latestAppVersionCode(installedCode: Int, catalogCode: Int): Int =
    maxOf(installedCode.coerceAtLeast(0), catalogCode.coerceAtLeast(0))
