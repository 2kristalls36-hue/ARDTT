package com.nonamevpn.app.ui.profiles

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.profile.ProfileLinkCodec
import com.nonamevpn.app.profile.VpnProfile
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction

@Composable
fun ProfileShareDialog(
    profile: VpnProfile,
    onDismissRequest: () -> Unit,
    extraActions: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val link = ProfileLinkCodec.buildLink(profile)
    val qr = rememberQrBitmap(link, 720)

    NvpnDialog(
        title = "Ссылка ARDTT",
        onDismissRequest = onDismissRequest,
        dismissAction = NvpnDialogAction("Закрыть", onDismissRequest),
        confirmAction = NvpnDialogAction(
            text = "Поделиться",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                    putExtra(Intent.EXTRA_SUBJECT, profile.name)
                }
                context.startActivity(Intent.createChooser(send, "Отправить ссылку"))
            },
        ),
    ) {
        Text(
            "Зашифрованная ссылка и QR-код. Клиенту нужен ARDTT — импорт через «Добавить».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (qr != null) {
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
                        bitmap = qr,
                        contentDescription = "QR-код профиля",
                        modifier = Modifier
                            .size(220.dp)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
        Text(
            "Ссылка ARDTT",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
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
        extraActions()
    }
}

private fun copyLink(context: Context, link: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ARDTT link", link))
}
