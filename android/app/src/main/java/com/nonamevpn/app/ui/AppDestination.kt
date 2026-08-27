package com.nonamevpn.app.ui

/**
 * Bottom tabs in qWDTT order.
 * Deploy is reached from Servers (not a bottom tab). Settings lives under Tunnel.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val adminOnly: Boolean,
    val inBottomNav: Boolean = true,
) {
    Tunnel("tunnel", "Туннель", adminOnly = false),
    Servers("servers", "Серверы", adminOnly = true),
    Profiles("profiles", "Профили", adminOnly = false),
    Exceptions("exceptions", "Обход", adminOnly = false),
    Logs("logs", "Логи", adminOnly = false),
    /** Nested from Servers — not in bottom bar. */
    Deploy("deploy", "Деплой", adminOnly = true, inBottomNav = false),
    /** Opened from Tunnel — not in bottom bar. */
    Settings("settings", "Настройки", adminOnly = false, inBottomNav = false),
}
