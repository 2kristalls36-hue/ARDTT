package com.ardtt.app.ui.admin

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ardtt.app.ui.AppDestination
import com.ardtt.app.ui.components.layout.ArdttDestinationRow
import com.ardtt.app.ui.components.layout.ArdttFeedScaffold
import com.ardtt.app.ui.components.layout.ArdttTabHeader

@Composable
fun DiagnosticsScreen(
    testingVisible: Boolean,
    onOpenNetwork: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTesting: () -> Unit,
) {
    ArdttFeedScaffold(
        modifier = Modifier.fillMaxSize(),
        header = {
            ArdttTabHeader(
                title = "Диагностика",
                subtitle = "Сеть, журнал и проверка клиента",
            )
        },
    ) {
        ArdttDestinationRow(
            icon = Icons.Outlined.Wifi,
            title = "Сеть",
            subtitle = "Карта пути и задержки",
            onClick = onOpenNetwork,
        )
        ArdttDestinationRow(
            icon = Icons.Outlined.Terminal,
            title = "Журнал",
            subtitle = "События туннеля и деплоя",
            onClick = onOpenLogs,
        )
        if (testingVisible) {
            ArdttDestinationRow(
                icon = Icons.Outlined.Science,
                title = AppDestination.Testing.label,
                subtitle = "Запись и отправка журналов автору",
                onClick = onOpenTesting,
            )
        }
    }
}
