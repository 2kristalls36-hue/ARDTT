package com.ardtt.app.ui.components.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object ArdttPullRefreshDefaults {
    /** Extra hold so the top spinner is visible even when the refresh is instant. */
    const val MinVisibleMs = 450L

    /**
     * Absolute spinner Y under the status bar on every tab: the title row is
     * [ArdttSize.TitleRow] tall and sits below the header top padding.
     */
    val IndicatorTop: Dp =
        ArdttSize.TitleRow + ArdttHeaderDefaults.TopPaddingAfterStatusBar

    /** Material pull threshold / feed travel — same on every tab. */
    val FeedTravel: Dp = 80.dp
}

fun pullRefreshHoldMs(
    elapsedMs: Long,
    minMs: Long = ArdttPullRefreshDefaults.MinVisibleMs,
): Long = (minMs - elapsedMs).coerceAtLeast(0L)

/**
 * Full-screen pull-down refresh.
 *
 * The host must wrap the whole tab feed (header included) so the spinner sits
 * at [ArdttPullRefreshDefaults.IndicatorTop] and the feed slides by the same
 * threshold everywhere. Sticky CTA, search and tab bar stay outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArdttPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        state = state,
        indicator = {
            // Fixed circular shell while refreshing: the default indicator is
            // partially clipped on some vendor builds.
            if (refreshing) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = ArdttPullRefreshDefaults.IndicatorTop)
                        .size(ArdttSize.PullIndicator),
                    shape = ArdttShapes.Pill,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shadowElevation = ArdttElevation.Low,
                    tonalElevation = ArdttElevation.None,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(ArdttSize.Spinner),
                            strokeWidth = ArdttSize.StrokeThick,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        content = content,
    )
}

data class ArdttPullRefreshState(
    val refreshing: Boolean,
    val onRefresh: () -> Unit,
)

@Composable
fun rememberPullRefresh(block: suspend () -> Unit): ArdttPullRefreshState {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(block)
    return ArdttPullRefreshState(
        refreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                scope.launch {
                    refreshing = true
                    val started = System.currentTimeMillis()
                    try {
                        latest.value()
                    } finally {
                        delay(pullRefreshHoldMs(System.currentTimeMillis() - started))
                        refreshing = false
                    }
                }
            }
        },
    )
}
