package com.nonamevpn.app.core

/**
 * Target path after the user taps Авто / Прямое / Обход while a session is up.
 *
 * Returns null when Bypass was requested without a call hash — the tunnel
 * stays on the current path.
 *
 * Auto follows the last underlay probe (same as Connect). If that probe wants
 * Bypass but there is no hash, Direct is used instead of failing closed.
 */
fun resolveLiveSwitchPath(
    mode: ConnPathMode,
    currentPath: VpnPath,
    probePath: VpnPath?,
    hasCallHash: Boolean,
): VpnPath? {
    return when (mode) {
        ConnPathMode.Direct -> VpnPath.Direct
        ConnPathMode.Bypass -> if (hasCallHash) VpnPath.Bypass else null
        ConnPathMode.Auto -> {
            val preferred = probePath ?: currentPath
            if (preferred == VpnPath.Bypass && !hasCallHash) VpnPath.Direct else preferred
        }
    }
}

fun livePathRestartRequired(currentPath: VpnPath, targetPath: VpnPath): Boolean =
    currentPath != targetPath
