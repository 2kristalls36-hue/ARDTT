package com.ardtt.app.ui.profiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ardtt.app.profile.ProfileLinkCodec
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction

@Composable
fun ProfileShareDialog(
    profile: VpnProfile,
    onDismissRequest: () -> Unit,
    extraActions: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    val linkResult = remember(profile) {
        runCatching { ProfileLinkCodec.buildLink(profile) }
    }
    val link = linkResult.getOrDefault("")
    val linkError = linkResult.exceptionOrNull()?.message
    val qr = rememberQrBitmap(link, 512)

    ArdttDialog(
        title = "Ссылка ARDTT",
        onDismissRequest = onDismissRequest,
        dismissAction = ArdttDialogAction("Закрыть", onDismissRequest),
        confirmAction = ArdttDialogAction(
            text = "Поделиться",
            enabled = link.isNotBlank(),
            onClick = { shareLink(context, profile.name, link) },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxBodyHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Зашифрованная ссылка и QR-код. Клиенту нужен ARDTT — импорт через «Добавить».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (linkError != null) {
                Text(
                    "Не удалось собрать ссылку: $linkError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when {
                qr.bitmap != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Image(
                                bitmap = qr.bitmap,
                                contentDescription = "QR-код профиля",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                }
                qr.loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                    }
                }
            }
            Text(
                "Ссылка ARDTT",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (link.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        link,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = {
                            copyLink(context, link)
                            Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать")
                    }
                }
                OutlinedButton(
                    onClick = {
                        copyLink(context, link)
                        Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Копировать ссылку", modifier = Modifier.padding(start = 8.dp))
                }
            }
            extraActions()
        }
    }
}

private fun shareLink(context: Context, subject: String, link: String) {
    if (link.isBlank()) return
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        context.startActivity(
            Intent.createChooser(send, "Отправить ссылку").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { t ->
        Toast.makeText(context, t.message ?: "Не удалось поделиться", Toast.LENGTH_SHORT).show()
    }
}

private fun copyLink(context: Context, link: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ARDTT link", link))
}
