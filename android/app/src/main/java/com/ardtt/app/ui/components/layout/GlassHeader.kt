package com.ardtt.app.ui.components.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
import com.ardtt.app.ui.components.surface.GlassBackdrop
import com.ardtt.app.ui.components.surface.LiquidGlass
import com.ardtt.app.ui.components.surface.LiquidGlassPanel
import com.ardtt.app.ui.components.surface.glassBackdropSource
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.isDarkSurface

/**
 * Tab page whose content scrolls under a floating header. While nothing is under it the
 * header is just the title; once content passes beneath, a plate fades in behind the
 * title: liquid glass refracting the content when glass is on, the translucent shell otherwise.
 *
 * [content] gets the top padding to start below the header; [scrolled] says whether
 * content is currently under the header.
 */
@Composable
fun GlassHeaderPage(
    title: String,
    scrolled: Boolean,
    backdrop: GlassBackdrop?,
    actions: (@Composable RowScope.() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable (top: Dp) -> Unit,
) {
    var headerPx by remember { mutableStateOf(0) }
    val top = with(LocalDensity.current) { headerPx.toDp() }
    val plate by animateFloatAsState(if (scrolled) 1f else 0f, tween(260), label = "plate")
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .glassBackdropSource(backdrop, fill = MaterialTheme.colorScheme.background),
        ) { content(top) }

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // Whole height, status bar included: the content starts below it.
                .onSizeChanged { headerPx = it.height }
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (plate > 0.01f) HeaderPlate(backdrop, plate)
            ArdttPageHeader(title = title, actions = actions)
        }
        overlay()
    }
}

/**
 * Glass variant of [ArdttScrollChrome]: the header stays in place and a glass plate
 * fades in behind it once content passes beneath, refracting that content.
 */
@Composable
internal fun GlassScrollChrome(
    modifier: Modifier,
    backdrop: GlassBackdrop,
    header: @Composable () -> Unit,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable (top: Dp) -> Unit,
) {
    var headerPx by remember { mutableStateOf(0) }
    var scrolledPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val connection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (consumed.y != 0f) scrolledPx = (scrolledPx - consumed.y).coerceAtLeast(0f)
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val top = with(LocalDensity.current) { headerPx.toDp() } + com.ardtt.app.ui.theme.ArdttSpacing.Small
    val plate by animateFloatAsState(if (scrolledPx > 1f) 1f else 0f, tween(260), label = "plate")
    Box(
        modifier
            .fillMaxSize()
            .nestedScroll(connection),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .glassBackdropSource(backdrop, fill = MaterialTheme.colorScheme.background),
        ) { content(top) }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { headerPx = it.height }
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(horizontal = 10.dp, vertical = 2.dp)
                // Rows under the plate must not take taps meant for the header.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            if (plate > 0.01f) HeaderPlate(backdrop, plate)
            header()
        }
        if (overlay != null) {
            // Sticky chrome refracts this screen's recording, which it is not part of.
            androidx.compose.runtime.CompositionLocalProvider(
                com.ardtt.app.ui.components.surface.LocalButtonGlass provides backdrop,
            ) { overlay() }
        }
    }
}

@Composable
private fun BoxScope.HeaderPlate(backdrop: GlassBackdrop?, alpha: Float) {
    val colors = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    // Slides down a little as it appears.
    val m = Modifier
        .matchParentSize()
        .graphicsLayer {
            this.alpha = alpha
            translationY = (1f - alpha) * -8.dp.toPx()
        }
    if (backdrop != null && LiquidGlass.supported) {
        LiquidGlassPanel(
            backdrop = backdrop,
            cornerRadius = 24.dp,
            // Thicker veil than the tab bar: the title sits right over scrolling text.
            tint = colors.surface.copy(alpha = (backdrop.veil(dark) + 0.25f).coerceAtMost(0.92f)),
            dark = dark,
            blur = backdrop.frost(),
            modifier = m,
        ) {}
    } else {
        // Without glass the plate is nearly opaque: the translucent shell let the list
        // text show through the title.
        Box(
            m.clip(ArdttShapes.Section)
                .background(colors.surface.copy(alpha = 0.96f))
                .border(ArdttFloatingShell.shellBorder(), ArdttShapes.Section),
        )
    }
}
