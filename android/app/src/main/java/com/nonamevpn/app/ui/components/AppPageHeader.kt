package com.nonamevpn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared metrics so every bottom-tab title sits on the same Y. */
private val TabTitleRowHeight = 44.dp
private val TabHeaderTopAfterStatusBar = 8.dp

/**
 * Shared page chrome for tabs and nested admin screens.
 *
 * Title: headlineMedium ExtraBold primary.
 * Subtitle: bodyMedium onSurfaceVariant.
 * Actions sit on the row below the title so long titles are not clipped.
 *
 * Bottom tabs must use [AppTabPageHeader]: it owns the status-bar inset so
 * titles do not jump when a screen also uses [Arrangement.spacedBy].
 */
@Composable
fun AppPageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    applyStatusBarsPadding: Boolean = false,
    contentHorizontalPadding: Boolean = false,
    pinBelowStatusBar: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val onWallpaper = illustratedBackdropActive()
    val titleColor = backdropTitleColor()
    val subtitleColor = backdropMutedTextColor()
    val titleShadow = if (onWallpaper) {
        Shadow(
            color = Color.Black.copy(alpha = 0.42f),
            offset = Offset(0f, 1f),
            blurRadius = 8f,
        )
    } else {
        null
    }
    val startPad = when {
        onBack != null -> 4.dp
        contentHorizontalPadding -> 16.dp
        else -> 0.dp
    }
    val endPad = when {
        contentHorizontalPadding || onBack != null -> 8.dp
        else -> 0.dp
    }
    val topPad = if (pinBelowStatusBar) TabHeaderTopAfterStatusBar else 8.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (pinBelowStatusBar || applyStatusBarsPadding) {
                    Modifier.statusBarsPadding()
                } else {
                    Modifier
                },
            )
            .padding(start = startPad, end = endPad, top = topPad, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (pinBelowStatusBar) Modifier.height(TabTitleRowHeight) else Modifier),
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
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    shadow = titleShadow,
                ),
                color = titleColor,
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
                        style = MaterialTheme.typography.bodyMedium.copy(
                            shadow = titleShadow,
                        ),
                        color = subtitleColor,
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

/** Main bottom-tab chrome: status inset + title on a fixed row. */
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
        pinBelowStatusBar = true,
    )
}
