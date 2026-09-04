package com.ardtt.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom tabs: Tunnel → Network (admin) → Servers → Profiles → Exceptions → Logs → Settings.
 * Deploy is reached from Servers (not a bottom tab).
 * Testing appears when testing mode is on, and stays visible while a session is recording.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val navLabel: String = label,
    val adminOnly: Boolean,
    val inBottomNav: Boolean = true,
) {
    Tunnel("tunnel", "Туннель", navLabel = "Туннель", adminOnly = false),
    Network("network", "Сеть", adminOnly = true),
    Servers("servers", "Серверы", navLabel = "Сервера", adminOnly = true),
    Profiles("profiles", "Профили", adminOnly = false),
    Exceptions("exceptions", "Обход", adminOnly = false),
    Logs("logs", "Логи", adminOnly = false),
    Settings("settings", "Настройки", navLabel = "Настр.", adminOnly = false),
    Testing("testing", "Тестирование", navLabel = "Тест", adminOnly = false),
    /** Nested from Servers — not in bottom bar. */
    Deploy("deploy", "Деплой", navLabel = "Деплой", adminOnly = true, inBottomNav = false),
    ;

    fun navIcon(): ImageVector = when (this) {
        Tunnel -> Icons.Outlined.VpnKey
        Servers -> Icons.Outlined.Cloud
        Profiles -> Icons.Outlined.Folder
        Exceptions -> Icons.Outlined.FilterList
        Network -> Icons.Outlined.Wifi
        Logs -> Icons.Outlined.Terminal
        Deploy -> Icons.Outlined.CloudUpload
        Settings -> Icons.Outlined.Settings
        Testing -> Icons.Outlined.Science
    }
}
