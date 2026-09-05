package com.ardtt.app.ui.components.layout

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttMotion
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.selectedControlContainer
import kotlin.math.abs

data class ArdttNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val badgeCount: Int = 0,
)

private object NavBarDefaults {
    val Easing = CubicBezierEasing(0.2f, 0.9f, 0.24f, 1f)
    val OuterPadding = ArdttSpacing.SmallPlus
    val TrackPadding = ArdttSpacing.Small
    val LabelSize = 10.sp

    /** Emphasis above which a tab label turns semibold / fully opaque. */
    const val BoldEmphasis = 0.55f
    const val OpaqueEmphasis = 0.4f
    const val FadedLabelAlpha = 0.5f
    const val MaxBadgeCount = 99
}

/** Floating pill bottom bar with a sliding selection indicator. */
@Composable
fun ArdttNavigationBar(
    items: List<ArdttNavItem>,
    selectedRoute: String,
    onSelect: (String) -> Unit,
    dragTargetIndex: Int = -1,
    dragProgress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val selectedColor = colors.primary
    val unselectedColor = colors.onSurfaceVariant.copy(alpha = ArdttAlpha.Subtle)
    val indicatorColor = selectedControlContainer()

    val indicatorIndex = remember { Animatable(0f) }
    val selectedVisualIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)

    LaunchedEffect(selectedVisualIndex, items) {
        if (dragTargetIndex !in items.indices) {
            indicatorIndex.animateTo(
                targetValue = selectedVisualIndex.toFloat(),
                animationSpec = tween(
                    durationMillis = ArdttMotion.Indicator,
                    easing = NavBarDefaults.Easing,
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
            .padding(
                horizontal = NavBarDefaults.OuterPadding,
                vertical = NavBarDefaults.TrackPadding,
            ),
    ) {
        val itemWidth =
            (maxWidth - NavBarDefaults.TrackPadding * 2) / items.size.coerceAtLeast(1)
        val indicatorOffset = NavBarDefaults.TrackPadding + itemWidth * dragVisualIndex

        Surface(
            shape = ArdttShapes.Section,
            color = ArdttFloatingShell.shellColor(),
            border = ArdttFloatingShell.shellBorder(),
            tonalElevation = ArdttElevation.None,
            shadowElevation = ArdttFloatingShell.shadowElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ArdttSize.NavTrack),
            ) {
                Surface(
                    shape = ArdttShapes.Control,
                    color = indicatorColor,
                    modifier = Modifier
                        .offset { IntOffset(x = indicatorOffset.roundToPx(), y = 0) }
                        .padding(vertical = NavBarDefaults.TrackPadding)
                        .width(itemWidth)
                        .fillMaxHeight(),
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = NavBarDefaults.TrackPadding,
                            vertical = ArdttSpacing.TinyPlus,
                        ),
                ) {
                    items.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - dragVisualIndex)).coerceIn(0f, 1f)
                        NavBarTab(
                            item = item,
                            emphasis = emphasis,
                            iconColor = lerp(unselectedColor, selectedColor, emphasis),
                            onSelect = { onSelect(item.route) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavBarTab(
    item: ArdttNavItem,
    emphasis: Float,
    iconColor: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(ArdttShapes.Menu)
            .clickable(onClick = onSelect),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                modifier = Modifier.size(ArdttSize.Icon),
                tint = iconColor,
            )
            if (item.badgeCount > 0) {
                Badge(
                    modifier = Modifier.offset(
                        x = ArdttSpacing.Medium,
                        y = -ArdttSpacing.Small,
                    ),
                ) {
                    Text("${item.badgeCount.coerceAtMost(NavBarDefaults.MaxBadgeCount)}")
                }
            }
        }
        Spacer(modifier = Modifier.height(ArdttSpacing.Tiny))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = NavBarDefaults.LabelSize,
                letterSpacing = 0.sp,
            ),
            fontWeight = if (emphasis > NavBarDefaults.BoldEmphasis) {
                FontWeight.SemiBold
            } else {
                FontWeight.Normal
            },
            color = iconColor.copy(
                alpha = if (emphasis > NavBarDefaults.OpaqueEmphasis) {
                    1f
                } else {
                    NavBarDefaults.FadedLabelAlpha
                },
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}
