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
fun LogsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Логи", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Подробные логи probe / AWG / TURN·RAW с экспортом появятся здесь. Лог деплоя смотрите на вкладке «Деплой».",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Вкладка видна только в режиме администратора.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
