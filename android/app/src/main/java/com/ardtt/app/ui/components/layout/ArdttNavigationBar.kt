package com.ardtt.app.ui.components.layout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttMotion
import com.ardtt.app.ui.theme.ArdttNavigationLabelStyle
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

internal object ArdttNavChrome {
    val LabelSize = ArdttNavigationLabelStyle.fontSize
    val LabelLineHeight = ArdttNavigationLabelStyle.lineHeight
}

private object NavBarDefaults {
    val Easing = CubicBezierEasing(0.2f, 0.9f, 0.24f, 1f)
    val OuterPadding = ArdttSpacing.SmallPlus
    val TrackPadding = ArdttSpacing.Small
    val IndicatorInset = ArdttSpacing.TinyPlus

    const val BoldEmphasis = 0.55f
    const val OpaqueEmphasis = 0.4f
    const val FadedLabelAlpha = 0.92f
    const val MaxBadgeCount = 99
}

internal data class NavTabPaint(
    val color: Color,
    val bold: Boolean,
    val labelAlpha: Float,
)

internal fun navTabPaint(
    selected: Boolean,
    pending: Boolean,
    pressed: Boolean,
    emphasis: Float,
    selectedColor: Color,
    unselectedColor: Color,
): NavTabPaint {
    val highlighted = selected || pending || pressed
    val visualEmphasis = if (highlighted) 1f else emphasis
    return NavTabPaint(
        color = if (highlighted) {
            selectedColor
        } else {
            lerp(unselectedColor, selectedColor, emphasis)
        },
        bold = visualEmphasis > NavBarDefaults.BoldEmphasis,
        labelAlpha = if (visualEmphasis > NavBarDefaults.OpaqueEmphasis) {
            1f
        } else {
            NavBarDefaults.FadedLabelAlpha
        },
    )
}

internal fun navTabPendingRoute(currentRoute: String, clickedRoute: String): String? =
    clickedRoute.takeIf { it != currentRoute }

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
    val unselectedColor = colors.onSurfaceVariant
    val indicatorColor = selectedControlContainer()

    val indicatorIndex = remember { Animatable(0f) }
    val selectedVisualIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    val selectTab = rememberUpdatedState(onSelect)

    LaunchedEffect(selectedRoute) {
        pendingRoute = null
    }

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
                        .padding(vertical = NavBarDefaults.IndicatorInset)
                        .width(itemWidth)
                        .fillMaxHeight(),
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .selectableGroup()
                        .padding(
                            horizontal = NavBarDefaults.TrackPadding,
                            vertical = ArdttSpacing.Tiny,
                        ),
                ) {
                    items.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - dragVisualIndex)).coerceIn(0f, 1f)
                        NavBarTab(
                            item = item,
                            selected = item.route == selectedRoute,
                            pending = item.route == pendingRoute,
                            emphasis = emphasis,
                            selectedColor = selectedColor,
                            unselectedColor = unselectedColor,
                            onSelect = {
                                navTabPendingRoute(selectedRoute, item.route)?.let { pendingRoute = it }
                                selectTab.value(item.route)
                            },
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
    selected: Boolean,
    pending: Boolean,
    emphasis: Float,
    selectedColor: Color,
    unselectedColor: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val paint = navTabPaint(
        selected = selected,
        pending = pending,
        pressed = pressed,
        emphasis = emphasis,
        selectedColor = selectedColor,
        unselectedColor = unselectedColor,
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onSelect,
            )
            .semantics { this.selected = selected },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(ArdttSize.Icon),
                tint = paint.color,
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
        Spacer(modifier = Modifier.height(ArdttSpacing.Hairline))
        Text(
            text = item.label,
            modifier = Modifier.fillMaxWidth(),
            style = ArdttNavigationLabelStyle.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            fontWeight = if (paint.bold) FontWeight.SemiBold else FontWeight.Normal,
            color = paint.color.copy(alpha = paint.labelAlpha),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
        )
    }
}
