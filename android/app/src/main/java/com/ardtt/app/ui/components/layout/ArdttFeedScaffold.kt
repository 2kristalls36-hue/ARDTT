package com.ardtt.app.ui.components.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttSpacing

/** Status-bar spacer for screens that do not use [ArdttScrollChrome]. */
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
 * The title sits in [ArdttScrollChrome] and dissolves as the feed scrolls,
 * then returns on the same curve when scrolling back. Content fades into the
 * blur strip under the chrome. Passing [stickyContent] pins a full-width
 * action above the pill.
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
    header: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val bottomPadding = scrollBottomPadding
        ?: if (stickyContent != null) {
            ArdttBottomChrome.scrollContentPadding()
        } else {
            ArdttBottomChrome.navigationReserve() + bottomExtra
        }

    ArdttScrollChrome(
        modifier = modifier,
        header = header,
    ) { topPad ->
        val feed: @Composable (Modifier) -> Unit = { feedModifier ->
            Column(
                modifier = feedModifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = horizontalPadding)
                    .padding(top = topPad, bottom = bottomPadding),
                verticalArrangement = verticalArrangement,
            ) {
                content()
            }
        }
        val host: @Composable (Modifier) -> Unit = { hostModifier ->
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
            host(Modifier)
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                host(Modifier)
                ArdttStickyBottomBar(horizontalPadding = horizontalPadding, content = stickyContent)
            }
        }
    }
}

/** Lazy feed with the same pinned chrome and sticky CTA as [ArdttFeedScaffold]. */
@Composable
fun ArdttLazyFeedScaffold(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    horizontalPadding: Dp = ArdttLayout.ScreenPadding,
    bottomExtra: Dp = ArdttLayout.FeedBottomExtra,
    scrollBottomPadding: Dp? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    stickyContent: (@Composable BoxScope.() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val bottomPadding = scrollBottomPadding
        ?: if (stickyContent != null) {
            ArdttBottomChrome.scrollContentPadding()
        } else {
            ArdttBottomChrome.navigationReserve() + bottomExtra
        }

    ArdttScrollChrome(
        modifier = modifier,
        header = header,
    ) { topPad ->
        val feed: @Composable (Modifier) -> Unit = { feedModifier ->
            LazyColumn(
                state = listState,
                modifier = feedModifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = topPad,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(ArdttLayout.ListSpacing),
                content = content,
            )
        }
        val host: @Composable (Modifier) -> Unit = { hostModifier ->
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
            host(Modifier)
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                host(Modifier)
                ArdttStickyBottomBar(horizontalPadding = horizontalPadding, content = stickyContent)
            }
        }
    }
}

/**
 * Pins [content] above the floating tab pill.
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
