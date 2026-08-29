package com.nonamevpn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
 * Shared page chrome for tabs and nested admin screens.
 *
 * Title: headlineMedium ExtraBold primary.
 * Subtitle: bodyMedium onSurfaceVariant.
 * Actions sit on the row below the title so long titles are not clipped.
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
    actions: (@Composable RowScope.() -> Unit)? = null,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (applyStatusBarsPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(start = startPad, end = endPad, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!subtitle.isNullOrBlank() || actions != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onBack != null) Modifier.padding(start = 48.dp) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (actions != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

/** Main bottom-tab chrome: the tab’s own title, not the app name. */
@Composable
fun AppTabPageHeader(
    title: String,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    AppPageHeader(
        title = title,
        subtitle = subtitle,
        actions = actions,
    )
}
