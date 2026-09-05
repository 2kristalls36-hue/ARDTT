package com.ardtt.app.ui.components.layout

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttSpacing

/** Scrollable spacer for the status bar. Title top pad lives in [ArdttTabHeader]. */
@Composable
fun ArdttStatusBarInset(extra: Dp = ArdttSpacing.None) {
    Spacer(
        Modifier.height(
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + extra,
        ),
    )
}

/**
 * The one scroll container of the app.
 *
 * Content runs edge-to-edge under the status bar and under the floating tab
 * pill. Passing [stickyContent] pins a full-width action above the pill and
 * reserves feed space for it; leaving it null gives the plain settings/logs
 * feed. Both variants share the header anchor and the pull-to-refresh host, so
 * titles land on the same baseline and the spinner at the same Y on every tab.
 */
@Composable
fun ArdttFeedScaffold(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    horizontalPadding: Dp = ArdttLayout.ScreenPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(ArdttLayout.FeedSpacing),
    bottomExtra: Dp = ArdttLayout.FeedBottomExtra,
    scrollBottomPadding: Dp? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    stickyContent: (@Composable BoxScope.() -> Unit)? = null,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val bottomPadding = scrollBottomPadding
        ?: if (stickyContent != null) {
            ArdttBottomChrome.scrollContentPadding()
        } else {
            ArdttBottomChrome.navigationReserve() + bottomExtra
        }

    val feed: @Composable (Modifier) -> Unit = { feedModifier ->
        Column(
            modifier = feedModifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = horizontalPadding)
                .padding(bottom = bottomPadding),
            verticalArrangement = verticalArrangement,
        ) {
            ArdttHeaderAnchor { header() }
            content()
        }
    }

    val refreshable: @Composable (Modifier) -> Unit = { hostModifier ->
        if (onRefresh != null) {
            ArdttPullRefresh(
                refreshing = refreshing,
                onRefresh = onRefresh,
                modifier = hostModifier.fillMaxSize(),
            ) {
                feed(Modifier)
            }
        } else {
            feed(hostModifier)
        }
    }

    if (stickyContent == null) {
        refreshable(modifier)
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        refreshable(Modifier)
        ArdttStickyBottomBar(horizontalPadding = horizontalPadding, content = stickyContent)
    }
}

/**
 * Pins [content] above the floating tab pill.
 *
 * Screens that scroll with a `LazyColumn` cannot use [ArdttFeedScaffold], but
 * their CTA must line up with the ones that can — this is the shared placement
 * those screens used to spell out modifier by modifier.
 */
@Composable
fun BoxScope.ArdttStickyBottomBar(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = ArdttLayout.ScreenPadding,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .zIndex(StickyZIndex)
            .padding(horizontal = horizontalPadding)
            .padding(bottom = ArdttBottomChrome.stickyBottomPadding()),
        content = content,
    )
}

/** Sticky chrome must paint above feed content and its refresh indicator. */
private const val StickyZIndex = 2f
