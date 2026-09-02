package com.nonamevpn.app.ui

/**
 * Bottom tabs: Tunnel → Servers → Profiles → Exceptions → Network (admin) → Logs (admin) → Testing (admin, conditional).
 * Deploy is reached from Servers (not a bottom tab). Settings open from the tunnel gear sheet.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val navLabel: String = label,
    val adminOnly: Boolean,
    val inBottomNav: Boolean = true,
) {
    Tunnel("tunnel", "Туннель", adminOnly = false),
    Servers("servers", "Серверы", navLabel = "Сервера", adminOnly = true),
    Profiles("profiles", "Профили", adminOnly = false),
    Exceptions("exceptions", "Обход", adminOnly = false),
    Network("network", "Сеть", adminOnly = true),
    Logs("logs", "Логи", adminOnly = true),
    Testing("testing", "Тестирование", navLabel = "Тест", adminOnly = true),
    /** Nested from Servers — not in bottom bar. */
    Deploy("deploy", "Деплой", adminOnly = true, inBottomNav = false),
}
