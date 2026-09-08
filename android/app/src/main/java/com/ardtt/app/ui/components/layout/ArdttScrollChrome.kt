package com.ardtt.app.ui.components.layout

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttChrome
import com.ardtt.app.ui.theme.isDarkSurface

/**
 * Pins a sharp header over scrolling content and blurs the strip that
 * disappears under it.
 *
 * Layering (API 31+): the feed is recorded into one offscreen [androidx.compose.ui.graphics.layer.GraphicsLayer]
 * and drawn once. A clipped overlay draws that same layer again with a GPU
 * [BlurEffect], then a vertical scrim. The header and status icons sit above,
 * unblurred. API 28–30 skip RenderEffect and keep the fade+scrim so text never
 * competes with the clock.
 *
 * Do not copy the list into a second composition. The fade overlay consumes
 * pointers so hidden rows are not clickable or announced twice; the header
 * row itself is a later sibling and keeps its own taps.
 */
@Composable
fun ArdttScrollChrome(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (topContentPadding: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val status = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues()
        .calculateTopPadding()
    var headerHeight by remember { mutableStateOf(ArdttHeaderDefaults.TitleRowHeight) }
    val fade = ArdttChrome.FadeHeight
    val chromeHeight = status + headerHeight
    val topPadding = chromeHeight
    val graphicsLayer = rememberGraphicsLayer()
    val useBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dark = isDarkSurface()
    val scrim = MaterialTheme.colorScheme.background.copy(
        alpha = if (dark) ArdttChrome.ScrimAlphaDark else ArdttChrome.ScrimAlphaLight,
    )
    val blurPx = with(density) { ArdttChrome.BlurRadius.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val feed = this
                    graphicsLayer.record(
                        density = density,
                        layoutDirection = layoutDirection,
                        size = IntSize(
                            size.width.toInt().coerceAtLeast(1),
                            size.height.toInt().coerceAtLeast(1),
                        ),
                    ) {
                        feed.drawContent()
                    }
                    drawLayer(graphicsLayer)
                },
        ) {
            content(topPadding)
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chromeHeight + fade),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clearAndSetSemantics {}
                        .then(
                            if (useBlur) {
                                Modifier.graphicsLayer {
                                    clip = true
                                    compositingStrategy = CompositingStrategy.Offscreen
                                    renderEffect = BlurEffect(
                                        blurPx,
                                        blurPx,
                                        TileMode.Clamp,
                                    )
                                }.drawWithContent {
                                    drawLayer(graphicsLayer)
                                }
                            } else {
                                Modifier
                            },
                        ),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to scrim,
                                0.55f to scrim.copy(alpha = scrim.alpha * ArdttChrome.FadeAlpha),
                                1f to scrim.copy(alpha = 0f),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(chromeHeight)
                        .consumeHiddenContentPointers(),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(fade)
                        .consumeHiddenContentPointers(),
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(status))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { measured ->
                                headerHeight = with(density) { measured.height.toDp() }.coerceAtLeast(0.dp)
                            },
                    ) {
                        header()
                    }
                }
            }
        }
    }
}

/** Eat hits that would otherwise reach rows drawn under the pinned chrome. */
private fun Modifier.consumeHiddenContentPointers(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}
