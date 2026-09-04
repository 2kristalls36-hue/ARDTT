package com.ardtt.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * Determinate bar for deploy / upload. Material3's default [LinearProgressIndicator]
 * draws a circular stop at the end of the track and a gap before the remaining
 * track — that shows up as a stray blue dot while progress is still low.
 */
@Composable
fun ArdttLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
) {
    val animated by animateFloatAsState(
        targetValue = coerceLinearProgress(progress),
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "ardttLinearProgress",
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50)),
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

internal fun coerceLinearProgress(progress: Float): Float = progress.coerceIn(0f, 1f)
