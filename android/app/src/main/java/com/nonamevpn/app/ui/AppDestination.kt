package com.nonamevpn.app.ui

/**
 * Bottom tabs: Tunnel → Servers → Profiles → Exceptions → Logs → Settings.
 * Deploy is reached from Servers (not a bottom tab).
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
    Settings("settings", "Настройки", adminOnly = false),
    /** Nested from Servers — not in bottom bar. */
    Deploy("deploy", "Деплой", adminOnly = true, inBottomNav = false),
}
