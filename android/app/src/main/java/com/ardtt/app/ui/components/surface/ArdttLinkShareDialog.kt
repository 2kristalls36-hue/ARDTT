package com.ardtt.app.ui.components.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.util.copyToClipboard
import com.ardtt.app.ui.util.shareText

/** Share sheets never take more than this share of the screen height. */
private const val MAX_BODY_HEIGHT_FRACTION = 0.55f

private const val LINK_MAX_LINES = 4

/**
 * QR + copyable link + share action.
 *
 * The profile export and the server export were two 190-line files differing
 * only in their wording and clip label; both are now thin wrappers over this.
 */
@Composable
fun ArdttLinkShareDialog(
    title: String,
    description: String,
    link: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    linkError: String? = null,
    shareSubject: String? = null,
    clipLabel: String = "ARDTT",
    qrContentDescription: String = "QR-код",
    oversizedQrNote: String? = null,
    extraContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * MAX_BODY_HEIGHT_FRACTION).dp
    val qr = rememberQrCode(link)
    val copy = { copyToClipboard(context, link, clipLabel, "Ссылка скопирована") }

    ArdttDialog(
        title = title,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissAction = ArdttDialogAction("Закрыть", onDismissRequest),
        confirmAction = ArdttDialogAction(
            text = "Поделиться",
            enabled = link.isNotBlank(),
            onClick = { shareText(context, link, shareSubject, "Отправить ссылку") },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxBodyHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
        ) {
            Text(
                description,
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
                qr.bitmap != null -> ArdttQrCode(
                    bitmap = qr.bitmap,
                    contentDescription = qrContentDescription,
                )
                qr.loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ArdttSpacing.XXLarge),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ArdttSize.SpinnerLarge),
                        strokeWidth = ArdttSize.Stroke,
                    )
                }
                link.isNotBlank() && oversizedQrNote != null -> Text(
                    oversizedQrNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
                ) {
                    Text(
                        link,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                        maxLines = LINK_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ArdttButton(
                        onClick = copy,
                        variant = ArdttButtonVariant.Icon,
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "Копировать",
                    )
                }
                ArdttButton(
                    text = "Копировать ссылку",
                    onClick = copy,
                    variant = ArdttButtonVariant.Outlined,
                    icon = Icons.Default.ContentCopy,
                )
            }
            extraContent()
        }
    }
}
