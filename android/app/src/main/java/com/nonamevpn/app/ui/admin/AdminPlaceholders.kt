package com.nonamevpn.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ServersScreen() {
    AdminPlaceholder(
        title = "Серверы",
        body = "Здесь будет управление профилями и provision (host_id, выдача nvpn).",
    )
}

@Composable
fun DeployScreen() {
    AdminPlaceholder(
        title = "Деплой",
        body = "Здесь будет установка Compose-стека на VPS по SSH (как Deploy в qWDTT).",
    )
}

@Composable
fun LogsScreen() {
    AdminPlaceholder(
        title = "Логи",
        body = "Здесь будут подробные логи probe / AWG / TURN·RAW с экспортом.",
    )
}

@Composable
private fun AdminPlaceholder(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Вкладка видна только в режиме администратора.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
