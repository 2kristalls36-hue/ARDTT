package com.ardtt.app.ui.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ServerLinkCodec
import com.ardtt.app.ui.components.surface.ArdttLinkShareDialog

@Composable
fun ServerShareDialog(
    servers: List<DeployTarget>,
    onDismissRequest: () -> Unit,
) {
    val linkResult = remember(servers) {
        runCatching { ServerLinkCodec.buildLink(servers) }
    }
    val subject = if (servers.size == 1) {
        val only = servers.first()
        only.name.ifBlank { only.host }.ifBlank { "Сервер" }
    } else {
        "Серверы (${servers.size})"
    }
    ArdttLinkShareDialog(
        title = "Экспорт серверов",
        description = "Закрытая ссылка и QR-код. На другом устройстве откройте ссылку в ARDTT — " +
            "серверы появятся во вкладке «Серверы».",
        link = linkResult.getOrDefault(""),
        onDismissRequest = onDismissRequest,
        linkError = linkResult.exceptionOrNull()?.message,
        shareSubject = subject,
        clipLabel = "ARDTT servers",
        qrContentDescription = "QR-код экспорта серверов",
        oversizedQrNote = "QR слишком большой для этой выборки — скопируйте или отправьте ссылку.",
    )
}
