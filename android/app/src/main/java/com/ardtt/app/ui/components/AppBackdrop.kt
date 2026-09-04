package com.ardtt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Soft gradient + glow orbs behind non-tunnel screens. */
@Composable
fun AppBackdrop(
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val baseBrush = remember(colors.background, colors.surface, colors.surfaceVariant) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    lerp(colors.background, colors.surface, 0.18f),
                    colors.background,
                    lerp(colors.surfaceVariant, colors.background, 0.72f),
                )
            } else {
                listOf(
                    lerp(colors.background, colors.surface, 0.78f),
                    colors.background,
                    lerp(colors.surfaceVariant, colors.background, 0.30f),
                )
            },
        )
    }

    val topGlow = if (isDark) {
        colors.primary.copy(alpha = 0.04f)
    } else {
        lerp(colors.primary, colors.primaryContainer, 0.72f).copy(alpha = 0.22f)
    }
    val leftGlow = if (isDark) {
        colors.tertiary.copy(alpha = 0.03f)
    } else {
        lerp(colors.tertiary, colors.secondaryContainer, 0.74f).copy(alpha = 0.16f)
    }
    val bottomGlow = if (isDark) {
        colors.primary.copy(alpha = 0.028f)
    } else {
        lerp(colors.secondary, colors.primaryContainer, 0.70f).copy(alpha = 0.14f)
    }
    val lightOrbOutline = colors.outlineVariant.copy(alpha = 0.18f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBrush),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-86).dp, y = (-126).dp)
                .size(258.dp)
                .clip(CircleShape)
                .background(topGlow)
                .then(
                    if (isDark) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, lightOrbOutline, CircleShape)
                    },
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-44).dp, y = 28.dp)
                .size(146.dp)
                .clip(CircleShape)
                .background(leftGlow)
                .then(
                    if (isDark) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, lightOrbOutline.copy(alpha = 0.22f), CircleShape)
                    },
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 72.dp, y = 96.dp)
                .size(220.dp)
                .clip(CircleShape)
                .background(bottomGlow),
        )
    }
}
