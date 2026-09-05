package com.ardtt.app.ui.components.layout

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.isDarkSurface

/** Placement and size of one decorative glow orb. */
private data class GlowOrb(
    val alignment: Alignment,
    val offsetX: Dp,
    val offsetY: Dp,
    val size: Dp,
    val outlined: Boolean,
)

private val GlowOrbs = listOf(
    GlowOrb(Alignment.TopStart, (-86).dp, (-126).dp, 258.dp, outlined = true),
    GlowOrb(Alignment.CenterStart, (-44).dp, 28.dp, 146.dp, outlined = true),
    GlowOrb(Alignment.BottomEnd, 72.dp, 96.dp, 220.dp, outlined = false),
)

/** Soft gradient + glow orbs behind non-tunnel screens. */
@Composable
fun ArdttBackdrop(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    val baseBrush = remember(colors.background, colors.surface, colors.surfaceVariant, dark) {
        Brush.verticalGradient(
            colors = if (dark) {
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

    val glowColors = if (dark) {
        listOf(
            colors.primary.copy(alpha = 0.04f),
            colors.tertiary.copy(alpha = 0.03f),
            colors.primary.copy(alpha = 0.028f),
        )
    } else {
        listOf(
            lerp(colors.primary, colors.primaryContainer, 0.72f).copy(alpha = 0.22f),
            lerp(colors.tertiary, colors.secondaryContainer, 0.74f).copy(alpha = 0.16f),
            lerp(colors.secondary, colors.primaryContainer, 0.70f).copy(alpha = 0.14f),
        )
    }
    val orbOutline = colors.outlineVariant.copy(alpha = ArdttAlpha.Fill)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBrush),
    ) {
        GlowOrbs.forEachIndexed { index, orb ->
            Box(
                modifier = Modifier
                    .align(orb.alignment)
                    .offset(x = orb.offsetX, y = orb.offsetY)
                    .size(orb.size)
                    .clip(CircleShape)
                    .background(glowColors[index])
                    .then(orbBorder(dark = dark, orb = orb, outline = orbOutline)),
            )
        }
    }
}

private fun orbBorder(dark: Boolean, orb: GlowOrb, outline: Color): Modifier = when {
    dark || !orb.outlined -> Modifier
    orb.alignment == Alignment.TopStart ->
        Modifier.border(ArdttSize.Border, outline, CircleShape)
    else ->
        Modifier.border(
            ArdttSize.Border,
            outline.copy(alpha = ArdttAlpha.Outline),
            CircleShape,
        )
}
