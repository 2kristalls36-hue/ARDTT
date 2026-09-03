package com.nonamevpn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Single anchor for every bottom-tab title row in a scrolling feed. */
object TabHeaderMetrics {
    val TitleRowHeight = 44.dp
    val TopPaddingAfterStatusBar = 8.dp
    val HorizontalPadding = 16.dp
    val BottomPaddingBelowTitle = 12.dp
}

/**
 * Status-bar spacer + title as **one** column child.
 *
 * Parent feeds use [Arrangement.spacedBy]; if the inset and title are siblings,
 * that gap sits *between* the status bar and the word, so titles jump by ~14.dp
 * from tab to tab. This wrapper keeps every tab title on the same baseline.
 */
@Composable
fun TabHeaderAnchor(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        EdgeFeedTopInset()
        content()
    }
}

/**
 * Anchored tab title row. Pair with [EdgeFeedTopInset] (or use [TabFeedHeader]).
 * Inside [EdgeFeedColumn] pass only this composable in [header] — the column
 * already applies [EdgeFeedTopInset].
 */
@Composable
fun TabPageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    PageHeaderChrome(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        actions = actions,
        topPadding = TabHeaderMetrics.TopPaddingAfterStatusBar,
        titleRowHeight = TabHeaderMetrics.TitleRowHeight,
        bottomPadding = TabHeaderMetrics.BottomPaddingBelowTitle,
    )
}

/** Status-bar inset + [TabPageHeader] for manual scroll feeds. */
@Composable
fun TabFeedHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    TabHeaderAnchor {
        TabPageHeader(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions,
        )
    }
}

/** Generic page header for dialogs and standalone screens (not bottom-tab anchor). */
@Composable
fun AppPageHeader(
    title: String,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    PageHeaderChrome(
        title = title,
        subtitle = subtitle,
        onBack = null,
        actions = actions,
        topPadding = 8.dp,
        titleRowHeight = null,
        bottomPadding = TabHeaderMetrics.BottomPaddingBelowTitle,
    )
}

@Composable
private fun PageHeaderChrome(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: (@Composable RowScope.() -> Unit)?,
    topPadding: Dp,
    titleRowHeight: Dp?,
    bottomPadding: Dp,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (titleRowHeight != null) Modifier.height(titleRowHeight) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(TabHeaderMetrics.TitleRowHeight),
                ) {
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
                    .then(if (onBack != null) Modifier.padding(start = TabHeaderMetrics.TitleRowHeight) else Modifier),
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
