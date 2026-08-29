package com.nonamevpn.app.ui

/**
 * Bottom tabs: Tunnel → Servers → Profiles → Exceptions → Logs → Settings.
 * Deploy is reached from Servers (not a bottom tab).
 * Testing appears only in admin mode when enabled in settings.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val navLabel: String = label,
    val adminOnly: Boolean,
    val inBottomNav: Boolean = true,
) {
    Tunnel("tunnel", "Туннель", navLabel = "VPN", adminOnly = false),
    Servers("servers", "Серверы", navLabel = "Сервера", adminOnly = true),
    Profiles("profiles", "Профили", adminOnly = false),
    Exceptions("exceptions", "Обход", adminOnly = false),
    Logs("logs", "Логи", adminOnly = false),
    Settings("settings", "Настройки", navLabel = "Настр.", adminOnly = false),
    Testing("testing", "Тестирование", navLabel = "Тест", adminOnly = true),
    /** Nested from Servers — not in bottom bar. */
    Deploy("deploy", "Деплой", navLabel = "Деплой", adminOnly = true, inBottomNav = false),
}
