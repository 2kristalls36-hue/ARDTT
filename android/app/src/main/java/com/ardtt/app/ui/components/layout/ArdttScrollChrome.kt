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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
 * Pins a sharp header over scrolling content and fades the strip that
 * disappears under it.
 *
 * Scrolling the feed down dissolves the tab title (alpha + slight rise) over
 * one header height; scrolling back restores it on the same curve. The status
 * bar inset stays so system icons keep a readable scrim.
 *
 * Layering (API 31+): the feed is recorded into one offscreen [androidx.compose.ui.graphics.layer.GraphicsLayer]
 * and drawn once with a DstIn alpha mask so sharp pixels become transparent
 * under the chrome (not bleached by a light scrim). The overlay draws that
 * layer again with a GPU [BlurEffect], then the inverse DstIn mask so blur
 * crossfades into sharp content. The header and status icons sit above,
 * unblurred. API 28–30 skip RenderEffect; content still fades by alpha.
 *
 * Content is padded by chrome + fade so the first row is clear at scroll 0.
 * Only the sharp header consumes hits; the fade strip does not steal taps.
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
    val topPadding = ardttScrollChromeTopPadding(chromeHeight, fade)
    val overlayHeight = chromeHeight + fade
    val graphicsLayer = rememberGraphicsLayer()
    val useBlur = ardttScrollChromeUsesGpuBlur(Build.VERSION.SDK_INT)
    val dark = isDarkSurface()
    val scrim = MaterialTheme.colorScheme.background.copy(
        alpha = if (dark) ArdttChrome.ScrimAlphaDark else ArdttChrome.ScrimAlphaLight,
    )
    val blurPx = with(density) { ArdttChrome.BlurRadius.toPx() }
    val collapseRangePx = with(density) { headerHeight.toPx() }.coerceAtLeast(1f)
    var collapseScrollPx by remember { mutableFloatStateOf(0f) }
    val collapseRangeState = rememberUpdatedState(collapseRangePx)
    val headerVisibility = ardttScrollChromeHeaderVisibility(collapseScrollPx, collapseRangePx)
    val headerInteractive = headerVisibility >= ArdttScrollChromeHeaderGoneAlpha
    val hitChrome = status + headerHeight * headerVisibility
    val contentMask = status + (headerHeight + fade) * headerVisibility
    val headerConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (consumed.y == 0f) return Offset.Zero
                val range = collapseRangeState.value
                val next = (collapseScrollPx - consumed.y).coerceIn(0f, range)
                if (next != collapseScrollPx) collapseScrollPx = next
                return Offset.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(headerConnection),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
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
                    val maskH = contentMask.toPx()
                    if (maskH > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = ardttScrollChromeContentFadeStops(),
                                startY = 0f,
                                endY = maskH,
                            ),
                            size = Size(size.width, maskH),
                            blendMode = BlendMode.DstIn,
                        )
                    }
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
                    .height(overlayHeight),
            ) {
                if (useBlur && headerVisibility > 0f) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clearAndSetSemantics {}
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                                alpha = headerVisibility
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colorStops = ardttScrollChromeBlurFadeStops(),
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer {
                                    renderEffect = BlurEffect(
                                        blurPx,
                                        blurPx,
                                        TileMode.Clamp,
                                    )
                                }
                                .drawWithContent {
                                    drawLayer(graphicsLayer)
                                },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(hitChrome.coerceAtLeast(status))
                        .background(
                            Brush.verticalGradient(
                                0f to scrim,
                                0.72f to scrim.copy(
                                    alpha = scrim.alpha * ArdttChrome.FadeAlpha *
                                        headerVisibility.coerceAtLeast(
                                            if (status > 0.dp) 0.55f else 0f,
                                        ),
                                ),
                                1f to scrim.copy(alpha = 0f),
                            ),
                        )
                        .consumeHiddenContentPointers(),
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(status))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = headerVisibility
                                translationY = -collapseRangePx *
                                    (1f - headerVisibility) *
                                    ArdttScrollChromeHeaderRiseFraction
                            }
                            .onSizeChanged { measured ->
                                if (measured.height > 0) {
                                    headerHeight = with(density) {
                                        measured.height.toDp()
                                    }.coerceAtLeast(0.dp)
                                }
                            }
                            .then(
                                if (headerInteractive) {
                                    Modifier
                                } else {
                                    Modifier.clearAndSetSemantics {}
                                },
                            ),
                    ) {
                        if (headerInteractive) {
                            header()
                        } else {
                            Spacer(Modifier.height(headerHeight))
                        }
                    }
                }
            }
        }
    }
}

internal fun ardttScrollChromeUsesGpuBlur(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.S

internal fun ardttScrollChromeTopPadding(chromeHeight: Dp, fade: Dp): Dp = chromeHeight + fade

/**
 * 1 = title fully visible, 0 = dissolved. Progress is linear over
 * [collapseScrollPx] of feed scroll so scrubbing back reverses the same curve.
 */
internal fun ardttScrollChromeHeaderVisibility(
    collapseScrollPx: Float,
    collapseRangePx: Float,
): Float {
    if (collapseRangePx <= 0f) return 1f
    return (1f - collapseScrollPx / collapseRangePx).coerceIn(0f, 1f)
}

/** Shared mid-stop where blur hands off to sharp content. */
internal const val ArdttScrollChromeFadeMid = 0.55f

/** How far the title rises (as a fraction of its height) while dissolving. */
internal const val ArdttScrollChromeHeaderRiseFraction = 0.35f

/** Below this alpha the header stops receiving semantics / focus. */
internal const val ArdttScrollChromeHeaderGoneAlpha = 0.04f

/** Alpha mask for sharp feed pixels: transparent under chrome, opaque below fade. */
internal fun ardttScrollChromeContentFadeStops(): Array<Pair<Float, Color>> = arrayOf(
    0f to Color.Transparent,
    ArdttScrollChromeFadeMid to Color.Transparent,
    1f to Color.Black,
)

/** Inverse mask for the blur overlay: full under chrome, gone below fade. */
internal fun ardttScrollChromeBlurFadeStops(): Array<Pair<Float, Color>> = arrayOf(
    0f to Color.Black,
    ArdttScrollChromeFadeMid to Color.Black,
    1f to Color.Transparent,
)

/** Eat hits that would otherwise reach rows drawn under the pinned chrome. */
private fun Modifier.consumeHiddenContentPointers(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}
