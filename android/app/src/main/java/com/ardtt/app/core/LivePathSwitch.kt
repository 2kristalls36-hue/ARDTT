package com.ardtt.app.core

/**
 * Target path after the user taps Авто / Прямое / Обход while a session is up.
 *
 * Returns null when Bypass was requested without a call hash — the tunnel
 * stays on the current path.
 *
 * Auto on Wi‑Fi is always Direct. On cellular Auto follows the last underlay
 * probe (same as Connect). If that probe wants Bypass but there is no hash,
 * Direct is used instead of failing closed.
 */
fun resolveLiveSwitchPath(
    mode: ConnPathMode,
    currentPath: VpnPath,
    probePath: VpnPath?,
    hasCallHash: Boolean,
    underlayKind: UnderlayKind = UnderlayKind.Other,
): VpnPath? {
    return when (mode) {
        ConnPathMode.Direct -> VpnPath.Direct
        ConnPathMode.Bypass -> if (hasCallHash) VpnPath.Bypass else null
        ConnPathMode.Auto -> {
            if (autoUsesDirectOnWifi(mode, underlayKind)) return VpnPath.Direct
            val preferred = if (probePath == VpnPath.Bypass && currentPath == VpnPath.Direct) {
                currentPath
            } else {
                probePath ?: currentPath
            }
            if (preferred == VpnPath.Bypass && !hasCallHash) VpnPath.Direct else preferred
        }
    }
}

fun livePathRestartRequired(currentPath: VpnPath, targetPath: VpnPath): Boolean =
    currentPath != targetPath
