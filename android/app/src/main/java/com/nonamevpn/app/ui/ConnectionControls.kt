package com.nonamevpn.app.ui

/** Path / Hide-IP chips stay locked during a session unless the user unlocked them. */
internal fun connectionControlsLocked(
    sessionActive: Boolean,
    unlockWhileConnected: Boolean,
): Boolean = sessionActive && !unlockWhileConnected

/** Tunnel «Параметры подключения» card — hidden when the Settings toggle is on. */
internal fun tunnelConnectionParamsVisible(hideQuickSettings: Boolean): Boolean = !hideQuickSettings

internal fun latestAppVersionCode(installedCode: Int, catalogCode: Int): Int =
    maxOf(installedCode.coerceAtLeast(0), catalogCode.coerceAtLeast(0))
