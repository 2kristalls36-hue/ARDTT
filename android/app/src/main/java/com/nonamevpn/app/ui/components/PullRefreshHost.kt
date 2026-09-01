package com.nonamevpn.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val PULL_REFRESH_MIN_MS = 450L

/**
 * Absolute spinner Y under the status bar on every tab
 * (title row is ~44.dp + 8.dp top pad).
 */
val PULL_REFRESH_INDICATOR_TOP: Dp = 52.dp

/** Material pull threshold / feed travel — same on every tab. */
val PULL_REFRESH_FEED_TRAVEL: Dp = 80.dp

/** Extra hold so the top spinner is visible even when the refresh is instant. */
fun pullRefreshHoldMs(elapsedMs: Long, minMs: Long = PULL_REFRESH_MIN_MS): Long =
    (minMs - elapsedMs).coerceAtLeast(0L)

/**
 * Full-screen pull-down refresh.
 *
 * Host must wrap the whole tab feed (including [AppTabPageHeader]) so the spinner
 * sits at [PULL_REFRESH_INDICATOR_TOP] and the feed slides by the same Material
 * threshold everywhere. Sticky CTA / search / tab bar stay outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshHost(
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
            // Use a fixed circular shell while refreshing to avoid partial clipping
            // seen with the default indicator on some vendor builds.
            if (refreshing) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = PULL_REFRESH_INDICATOR_TOP)
                        .size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shadowElevation = 2.dp,
                    tonalElevation = 0.dp,
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        content = content,
    )
}

@Composable
fun rememberPullRefresh(block: suspend () -> Unit): PullRefreshController {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(block)
    return PullRefreshController(
        refreshing = refreshing,
        onRefresh = {
            if (refreshing) return@PullRefreshController
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
        },
    )
}

data class PullRefreshController(
    val refreshing: Boolean,
    val onRefresh: () -> Unit,
)
