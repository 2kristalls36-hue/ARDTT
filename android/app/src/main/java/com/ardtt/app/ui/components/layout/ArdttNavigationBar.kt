package com.ardtt.app.ui.components.layout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttMotion
import com.ardtt.app.ui.theme.ArdttNavigationLabelStyle
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import kotlin.math.abs
import kotlin.math.sin

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
    val OuterPadding = ArdttSpacing.SmallPlus
    val TrackPadding = ArdttSpacing.Small

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

/** One-second icon motion. Every pose is back at rest when progress is 0 or 1. */
internal enum class TabIconMotion {
    KeyTurn,
    Float,
    Lift,
    Slide,
    Pulse,
    Heartbeat,
    GearHalfTurn,
}

internal data class TabIconPose(
    val rotationZ: Float = 0f,
    val translationXFraction: Float = 0f,
    val translationYFraction: Float = 0f,
    val scale: Float = 1f,
)

internal fun tabIconMotionFor(route: String): TabIconMotion = when (route) {
    "tunnel" -> TabIconMotion.KeyTurn
    "servers" -> TabIconMotion.Float
    "profiles" -> TabIconMotion.Lift
    "exceptions" -> TabIconMotion.Slide
    "network" -> TabIconMotion.Pulse
    "diagnostics" -> TabIconMotion.Heartbeat
    "settings" -> TabIconMotion.GearHalfTurn
    else -> TabIconMotion.Pulse
}

/**
 * Smooth 0→1→0 weight. Velocity is zero at the start, the midpoint, and the end,
 * so a there-and-back motion does not kick.
 */
internal fun tabIconSettle(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    val wave = sin(Math.PI * t).toFloat()
    return wave * wave
}

/** Two beats inside the same second, also at rest at the ends and the middle. */
internal fun tabIconHeartbeat(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    val wave = sin(2.0 * Math.PI * t).toFloat()
    return wave * wave
}

/**
 * One-way 0→1 turn. Speed is zero at both ends, so a full circle starts and
 * stops on the same glyph without a kick.
 */
internal fun tabIconTurn(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun tabIconPose(motion: TabIconMotion, progress: Float): TabIconPose {
    val settle = tabIconSettle(progress)
    return when (motion) {
        TabIconMotion.GearHalfTurn -> TabIconPose(rotationZ = 180f * settle)
        TabIconMotion.KeyTurn -> TabIconPose(rotationZ = 360f * tabIconTurn(progress))
        TabIconMotion.Float -> TabIconPose(translationYFraction = -0.2f * settle)
        TabIconMotion.Lift -> TabIconPose(
            translationYFraction = -0.16f * settle,
            rotationZ = -8f * settle,
        )
        TabIconMotion.Slide -> TabIconPose(translationXFraction = 0.16f * settle)
        TabIconMotion.Pulse -> TabIconPose(scale = 1f + 0.12f * settle)
        TabIconMotion.Heartbeat -> TabIconPose(scale = 1f + 0.14f * tabIconHeartbeat(progress))
    }
}

internal fun tabIconPoseAtRest(pose: TabIconPose): Boolean {
    val wrapped = abs(pose.rotationZ % 360f)
    val rotationRest = wrapped < 0.05f || abs(wrapped - 360f) < 0.05f
    return rotationRest &&
        abs(pose.translationXFraction) < 0.01f &&
        abs(pose.translationYFraction) < 0.01f &&
        abs(pose.scale - 1f) < 0.01f
}

/** Floating pill bottom bar. The current tab is the icon and label, with no fill behind it. */
@Composable
fun ArdttNavigationBar(
    items: List<ArdttNavItem>,
    selectedRoute: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val selectedColor = colors.primary
    val unselectedColor = colors.onSurfaceVariant
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    val selectTab = rememberUpdatedState(onSelect)

    LaunchedEffect(selectedRoute) {
        pendingRoute = null
    }

    Surface(
        shape = ArdttShapes.Section,
        color = ArdttFloatingShell.shellColor(),
        border = ArdttFloatingShell.shellBorder(),
        tonalElevation = ArdttElevation.None,
        // A shadow graphics layer paints this translucent shell opaque for a frame.
        shadowElevation = ArdttElevation.None,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                horizontal = NavBarDefaults.OuterPadding,
                vertical = NavBarDefaults.TrackPadding,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ArdttSize.NavTrack),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup()
                    .padding(
                        horizontal = NavBarDefaults.TrackPadding,
                        vertical = ArdttSpacing.Tiny,
                    ),
            ) {
                items.forEach { item ->
                    NavBarTab(
                        item = item,
                        selected = item.route == selectedRoute,
                        pending = item.route == pendingRoute,
                        emphasis = 0f,
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
    val iconMotion = tabIconMotionFor(item.route)
    val iconProgress = remember { Animatable(0f) }
    var iconPlay by remember { mutableIntStateOf(0) }
    LaunchedEffect(iconPlay) {
        if (iconPlay == 0) return@LaunchedEffect
        iconProgress.snapTo(0f)
        iconProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = ArdttMotion.TabIcon,
                easing = LinearEasing,
            ),
        )
    }
    val iconPose = tabIconPose(iconMotion, iconProgress.value)
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
                onClick = {
                    if (!iconProgress.isRunning) iconPlay++
                    onSelect()
                },
            )
            .semantics { this.selected = selected },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(ArdttSize.Icon)
                    .graphicsLayer {
                        rotationZ = iconPose.rotationZ
                        translationX = iconPose.translationXFraction * size.width
                        translationY = iconPose.translationYFraction * size.height
                        scaleX = iconPose.scale
                        scaleY = iconPose.scale
                    },
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
