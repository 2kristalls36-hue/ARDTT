package com.nonamevpn.app.ui.components

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * Full-screen shell: scrollable edge-to-edge feed + sticky bottom bar
 * pinned above the floating tab bar.
 */
@Composable
fun StickyBottomScaffold(
    stickyContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.scrollContentPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EdgeFeedTopInset()
            content()
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

/** Scrollable spacer so the feed continues under the status bar. */
@Composable
fun EdgeFeedTopInset(extra: Dp = 8.dp) {
    Spacer(
        Modifier.height(
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + extra,
        ),
    )
}

/**
 * Edge-to-edge column without a sticky CTA (Settings / Logs style).
 * Still reserves space above the floating tab bar.
 */
@Composable
fun EdgeFeedColumn(
    modifier: Modifier = Modifier,
    bottomExtra: Dp = 24.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = NvpnBottomChrome.navigationReserve() + bottomExtra),
        verticalArrangement = verticalArrangement,
    ) {
        EdgeFeedTopInset()
        content()
    }
}

/** Primary full-width sticky action button (opaque). */
@Composable
fun StickyPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(NvpnBottomChrome.ButtonHeight),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 6.dp,
            pressedElevation = 2.dp,
            disabledElevation = 0.dp,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
