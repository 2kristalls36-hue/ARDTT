package com.nonamevpn.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.R
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction

fun resolveDonateUrl(context: Context, manifestDonateUrl: String?): String? {
    return manifestDonateUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: context.getString(R.string.donate_url).trim().takeIf { it.isNotEmpty() }
}

fun openDonatePage(context: Context, url: String) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
fun SupportProjectCard(
    donateUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showVpnHint by remember { mutableStateOf(false) }

    AppSectionCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.donate_section_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.donate_section_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { showVpnHint = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(stringResource(R.string.donate_open_button))
        }
    }

    if (showVpnHint) {
        NvpnDialog(
            title = stringResource(R.string.donate_section_title),
            onDismissRequest = { showVpnHint = false },
            dismissAction = NvpnDialogAction(
                text = "Отмена",
                onClick = { showVpnHint = false },
            ),
            confirmAction = NvpnDialogAction(
                text = "Перейти к оплате",
                onClick = {
                    showVpnHint = false
                    openDonatePage(context, donateUrl)
                },
            ),
        ) {
            Text(
                stringResource(R.string.donate_vpn_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
