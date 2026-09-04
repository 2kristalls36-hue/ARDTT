package com.ardtt.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

data class NavBarItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val badgeCount: Int = 0,
)

/** Floating pill bottom bar with sliding selection indicator (qWDTT-style). */
@Composable
fun ArdttNavigationBar(
    items: List<NavBarItem>,
    selectedRoute: String,
    onSelect: (String) -> Unit,
    dragTargetIndex: Int = -1,
    dragProgress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val selectedColor = colors.primary
    val unselectedColor = colors.onSurfaceVariant.copy(alpha = 0.72f)
    val shellColor = ArdttFloatingShell.shellColor()
    val isDark = ArdttFloatingShell.isDarkTheme()
    val indicatorColor = if (isDark) {
        colors.primary.copy(alpha = 0.18f)
    } else {
        lerp(colors.primaryContainer, colors.surface, 0.18f).copy(alpha = 0.94f)
    }

    val indicatorIndex = remember { Animatable(0f) }
    val selectedVisualIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)

    LaunchedEffect(selectedVisualIndex, items) {
        if (dragTargetIndex !in items.indices) {
            indicatorIndex.animateTo(
                targetValue = selectedVisualIndex.toFloat(),
                animationSpec = tween(
                    durationMillis = 720,
                    easing = CubicBezierEasing(0.2f, 0.9f, 0.24f, 1f),
                ),
            )
        }
    }
    LaunchedEffect(selectedVisualIndex, dragTargetIndex, dragProgress, items) {
        if (dragTargetIndex in items.indices) {
            val target = selectedVisualIndex.toFloat() +
                (dragTargetIndex - selectedVisualIndex) * dragProgress
            indicatorIndex.snapTo(target)
        }
    }
    val dragVisualIndex = indicatorIndex.value

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        val trackPadding = 8.dp
        val itemWidth = (maxWidth - trackPadding * 2) / items.size.coerceAtLeast(1)
        val indicatorOffset = trackPadding + itemWidth * dragVisualIndex

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = shellColor,
            border = ArdttFloatingShell.shellBorder(),
            tonalElevation = 0.dp,
            shadowElevation = ArdttFloatingShell.shadowElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = indicatorColor,
                    modifier = Modifier
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                x = indicatorOffset.roundToPx(),
                                y = 0,
                            )
                        }
                        .padding(vertical = 8.dp)
                        .width(itemWidth)
                        .fillMaxHeight(),
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = trackPadding, vertical = 6.dp),
                ) {
                    items.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - dragVisualIndex)).coerceIn(0f, 1f)
                        val iconColor = lerp(unselectedColor, selectedColor, emphasis)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { onSelect(item.route) },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(22.dp),
                                    tint = iconColor,
                                )
                                if (item.badgeCount > 0) {
                                    Badge(
                                        modifier = Modifier.offset(x = 12.dp, y = (-8).dp),
                                    ) {
                                        Text("${item.badgeCount.coerceAtMost(99)}")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    letterSpacing = 0.sp,
                                ),
                                fontWeight = if (emphasis > 0.55f) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                color = iconColor.copy(alpha = if (emphasis > 0.4f) 1f else 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
