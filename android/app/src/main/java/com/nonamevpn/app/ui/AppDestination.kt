package com.nonamevpn.app.ui

enum class AppDestination(val route: String, val label: String, val adminOnly: Boolean) {
    Tunnel("tunnel", "Туннель", adminOnly = false),
    Exceptions("exceptions", "Обход", adminOnly = false),
    Settings("settings", "Настройки", adminOnly = false),
    Servers("servers", "Серверы", adminOnly = true),
    Deploy("deploy", "Деплой", adminOnly = true),
    Logs("logs", "Логи", adminOnly = true),
}
