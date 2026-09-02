package com.nonamevpn.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * Shared chrome for floating bottom nav + sticky primary actions.
 * Content scrolls edge-to-edge (under status bar and under the sticky/nav zone).
 */
object NvpnBottomChrome {
    /** Matches floating [NvpnNavigationBar] overlay height. */
    val NavZoneHeight: Dp = 88.dp
    /** Air between sticky CTA and the tab pill. */
    val StickyGap: Dp = 6.dp
    val ButtonHeight: Dp = 58.dp

    /** Bottom padding so the last scroll items can pass under the sticky CTA. */
    @Composable
    fun navigationReserve(): Dp =
        NavZoneHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    @Composable
    fun stickyBottomPadding(): Dp = navigationReserve() + StickyGap

    @Composable
    fun scrollContentPadding(extra: Dp = 16.dp): Dp =
        navigationReserve() + StickyGap + ButtonHeight + extra
}

/**
 * Full-screen shell: sticky page header + scrollable feed + sticky bottom bar
 * pinned above the floating tab bar.
 *
 * Pass [header] inside the scroll feed so titles scroll away with the content.
 */
@Composable
fun StickyBottomScaffold(
    stickyContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val feed: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = NvpnBottomChrome.scrollContentPadding()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                EdgeFeedTopInset()
                header()
                content()
            }
        }
        if (onRefresh != null) {
            PullRefreshHost(
                refreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                feed()
            }
        } else {
            feed()
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(2f)
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.stickyBottomPadding()),
            content = stickyContent,
        )
    }
}

/** Scrollable spacer: status bar only. Title top pad lives in [TabPageHeader]. */
@Composable
fun EdgeFeedTopInset(extra: Dp = 0.dp) {
    Spacer(
        Modifier.height(
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + extra,
        ),
    )
}

/**
 * Edge-to-edge scrollable feed (Settings / Logs / nested admin style).
 * Header and content scroll together; [EdgeFeedTopInset] is applied once before [header].
 * Pass [TabPageHeader] in [header] (not [TabFeedHeader]).
 */
@Composable
fun EdgeFeedColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    bottomExtra: Dp = 24.dp,
    scrollBottomPadding: Dp? = null,
    horizontalPadding: Dp = TabHeaderMetrics.HorizontalPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(14.dp),
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val bottomPad = scrollBottomPadding ?: (NvpnBottomChrome.navigationReserve() + bottomExtra)
    val feed: @Composable (Modifier) -> Unit = { columnModifier ->
        Column(
            modifier = columnModifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = horizontalPadding)
                .padding(bottom = bottomPad),
            verticalArrangement = verticalArrangement,
        ) {
            EdgeFeedTopInset()
            header()
            content()
        }
    }
    if (onRefresh != null) {
        PullRefreshHost(
            refreshing = refreshing,
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
        ) {
            feed(Modifier)
        }
    } else {
        feed(modifier)
    }
}

/** Primary full-width sticky action. */
@Composable
fun StickyPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier
            .fillMaxWidth()
            .height(NvpnBottomChrome.ButtonHeight),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 6.dp,
            pressedElevation = 2.dp,
            disabledElevation = 0.dp,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.2.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
