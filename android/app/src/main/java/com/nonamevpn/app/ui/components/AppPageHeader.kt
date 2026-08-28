package com.nonamevpn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared page chrome for ARDTT tabs and nested admin screens.
 *
 * Title: headlineMedium ExtraBold primary.
 * Subtitle: bodyMedium onSurfaceVariant.
 *
 * When the parent Column already applies [statusBarsPadding] + horizontal 16.dp
 * (Tunnel / Profiles / …), leave [applyStatusBarsPadding] false.
 * Nested admin screens that own their own top inset should set it true.
 */
@Composable
fun AppPageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    applyStatusBarsPadding: Boolean = false,
    contentHorizontalPadding: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val startPad = when {
        onBack != null -> 4.dp
        contentHorizontalPadding -> 16.dp
        else -> 0.dp
    }
    val endPad = when {
        contentHorizontalPadding || onBack != null -> 8.dp
        else -> 0.dp
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (applyStatusBarsPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(start = startPad, end = endPad, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}
