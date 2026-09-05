package com.ardtt.app.ui.components.feedback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttMotion
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize

/** Hold time before the flash starts fading. */
private const val PING_HOLD_MS = 1000

/**
 * Small green circle that flashes when [pingKey] changes: lights up instantly,
 * holds for [holdMs], then fades over [fadeMs].
 */
@Composable
fun ArdttPingDot(
    pingKey: Any?,
    modifier: Modifier = Modifier,
    size: Dp = ArdttSize.Dot,
    color: Color = ArdttColors.Connected,
    holdMs: Int = PING_HOLD_MS,
    fadeMs: Int = ArdttMotion.Slow,
) {
    val alpha = remember { Animatable(0f) }
    var keyTracker by remember { mutableIntStateOf(0) }

    LaunchedEffect(pingKey) {
        if (pingKey != null) keyTracker++
    }
    LaunchedEffect(keyTracker) {
        if (keyTracker == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        alpha.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = holdMs))
        alpha.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = fadeMs))
    }

    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha.value)
            .clip(ArdttShapes.Pill)
            .background(color),
    )
}
