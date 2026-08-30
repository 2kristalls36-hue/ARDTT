package com.nonamevpn.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val PULL_REFRESH_MIN_MS = 450L

/** Extra hold so the top spinner is visible even when the refresh is instant. */
fun pullRefreshHoldMs(elapsedMs: Long, minMs: Long = PULL_REFRESH_MIN_MS): Long =
    (minMs - elapsedMs).coerceAtLeast(0L)

/**
 * Pull-down refresh: content slides with the gesture, a small wheel spins at the
 * top, and anything outside this host (sticky CTA, tab bar, search) stays pinned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshHost(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorStatusBarInset: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(if (indicatorStatusBarInset) Modifier.statusBarsPadding() else Modifier)
                    .padding(top = 6.dp),
                isRefreshing = refreshing,
                state = state,
                containerColor = MaterialTheme.colorScheme.surface,
                color = MaterialTheme.colorScheme.primary,
            )
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
