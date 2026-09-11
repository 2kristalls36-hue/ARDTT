package com.ardtt.app.ui.components.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.ArdttWallpaperTextShadow
import com.ardtt.app.ui.theme.backdropMutedTextColor
import com.ardtt.app.ui.theme.backdropTitleColor
import com.ardtt.app.ui.theme.illustratedBackdropActive

/** Single anchor for every bottom-tab title row in a scrolling feed. */
object ArdttHeaderDefaults {
    val TitleRowHeight: Dp = ArdttSize.TouchTarget
    val TopPaddingAfterStatusBar: Dp = ArdttSpacing.Small
    val HorizontalPadding: Dp = ArdttSpacing.Large
    val BottomPaddingBelowTitle: Dp = ArdttSpacing.Medium
}

/**
 * Status-bar spacer + title as **one** column child.
 *
 * Parent feeds use [Arrangement.spacedBy]; if the inset and title are siblings,
 * that gap sits *between* the status bar and the word, so titles jump by ~14.dp
 * from tab to tab. This wrapper keeps every tab title on the same baseline.
 */
@Composable
fun ArdttHeaderAnchor(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ArdttStatusBarInset()
        content()
    }
}

/**
 * Anchored tab title row. [ArdttScrollChrome] already consumes status-bar
 * and cutout insets, so pass this composable in the scaffold `header` slot.
 * Use [ArdttFeedHeader] only for scroll containers that do not use chrome.
 */
@Composable
fun ArdttTabHeader(
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
        topPadding = ArdttHeaderDefaults.TopPaddingAfterStatusBar,
        titleRowHeight = ArdttHeaderDefaults.TitleRowHeight,
    )
}

/** Status-bar inset + [ArdttTabHeader] for manual scroll feeds. */
@Composable
fun ArdttFeedHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    ArdttHeaderAnchor {
        ArdttTabHeader(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions,
        )
    }
}

/** Header for dialogs and standalone screens that are not bottom-tab roots. */
@Composable
fun ArdttPageHeader(
    title: String,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    PageHeaderChrome(
        title = title,
        subtitle = subtitle,
        onBack = null,
        actions = actions,
        topPadding = ArdttHeaderDefaults.TopPaddingAfterStatusBar,
        titleRowHeight = null,
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
) {
    val titleShadow = if (illustratedBackdropActive()) ArdttWallpaperTextShadow else null
    val minTitle = titleRowHeight ?: ArdttHeaderDefaults.TitleRowHeight
    val actionsOnTitleRow = actions != null && subtitle.isNullOrBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ArdttHeaderDefaults.HorizontalPadding,
                end = ArdttHeaderDefaults.HorizontalPadding,
                top = topPadding,
                bottom = ArdttHeaderDefaults.BottomPaddingBelowTitle,
            ),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minTitle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                ArdttButton(
                    onClick = onBack,
                    variant = ArdttButtonVariant.Icon,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    contentColor = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    shadow = titleShadow,
                ),
                color = backdropTitleColor(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionsOnTitleRow) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    subtitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(shadow = titleShadow),
                    color = backdropMutedTextColor(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
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
