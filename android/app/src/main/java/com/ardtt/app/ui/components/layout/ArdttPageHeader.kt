package com.ardtt.app.ui.components.layout

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
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.backdropMutedTextColor
import com.ardtt.app.ui.theme.backdropTitleColor
import com.ardtt.app.ui.theme.illustratedBackdropActive

/** Single anchor for every bottom-tab title row in a scrolling feed. */
object ArdttHeaderDefaults {
    val TitleRowHeight: Dp = ArdttSize.TitleRow
    val TopPaddingAfterStatusBar: Dp = ArdttSpacing.Small
    val HorizontalPadding: Dp = ArdttSpacing.Large
    val BottomPaddingBelowTitle: Dp = ArdttSpacing.Medium

    /** Blur of the title shadow used over the illustrated wallpaper. */
    const val TitleShadowBlur = 8f
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
    val titleShadow = if (illustratedBackdropActive()) {
        Shadow(
            color = Color.Black.copy(alpha = ArdttAlpha.Shadow),
            offset = Offset(0f, 1f),
            blurRadius = ArdttHeaderDefaults.TitleShadowBlur,
        )
    } else {
        null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = topPadding,
                bottom = ArdttHeaderDefaults.BottomPaddingBelowTitle,
            ),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Hairline),
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
                    modifier = Modifier.size(ArdttHeaderDefaults.TitleRowHeight),
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
                color = backdropTitleColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!subtitle.isNullOrBlank() || actions != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onBack != null) {
                            Modifier.padding(start = ArdttHeaderDefaults.TitleRowHeight)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = titleShadow),
                        color = backdropMutedTextColor(),
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
