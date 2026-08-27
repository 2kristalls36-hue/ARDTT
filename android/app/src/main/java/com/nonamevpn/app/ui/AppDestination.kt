package com.nonamevpn.app.ui

enum class AppDestination(val route: String, val label: String, val adminOnly: Boolean) {
    Tunnel("tunnel", "Туннель", adminOnly = false),
    Servers("servers", "Серверы", adminOnly = true),
    Profiles("profiles", "Профили", adminOnly = false),
    Exceptions("exceptions", "Обход", adminOnly = false),
    Logs("logs", "Логи", adminOnly = true),
}
