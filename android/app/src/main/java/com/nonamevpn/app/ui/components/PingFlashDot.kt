package com.nonamevpn.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.ui.theme.NvpnColors

/**
 * A small green circle that flashes when [pingKey] changes.
 * Lights up instantly, holds for [holdMs] ms, then fades over [fadeMs] ms.
 */
@Composable
fun PingFlashDot(
    pingKey: Any?,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    color: Color = NvpnColors.connected,
    holdMs: Int = 500,
    fadeMs: Int = 700,
) {
    val alpha = remember { Animatable(0f) }
    var lastKey by remember { mutableIntStateOf(0) }
    var keyTracker by remember { mutableIntStateOf(0) }

    LaunchedEffect(pingKey) {
        if (pingKey == null) return@LaunchedEffect
        keyTracker++
    }
    LaunchedEffect(keyTracker) {
        if (keyTracker == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = holdMs),
        )
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = fadeMs),
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha.value)
            .clip(CircleShape)
            .background(color),
    )
}
