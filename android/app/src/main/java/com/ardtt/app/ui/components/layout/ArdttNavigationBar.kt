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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.Icons
import com.ardtt.app.ui.components.surface.glassBackdropSource
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.animateFloatAsState
import com.ardtt.app.ui.motion.magnetTo
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.ardtt.app.ui.theme.isDarkSurface
import com.ardtt.app.ui.components.surface.LiquidGlassPanel
import com.ardtt.app.ui.components.surface.LiquidGlass
import com.ardtt.app.ui.components.surface.LocalGlassBackdrop
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
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
import com.ardtt.app.ui.theme.ArdttSurface
import com.ardtt.app.ui.theme.ArdttMotion
import com.ardtt.app.ui.theme.ArdttNavigationLabelStyle
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class ArdttNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val badgeCount: Int = 0,
    /** Small dot without a number, e.g. a new app version. */
    val dot: Boolean = false,
)

internal object ArdttNavChrome {
    val LabelSize = ArdttNavigationLabelStyle.fontSize
    val LabelLineHeight = ArdttNavigationLabelStyle.lineHeight
}

/** Pale green of a thick glass edge. */
private val GlassEdgeGreen = Color(0xFF8FD9B0)

private object NavBarDefaults {
    val GlassCorner = com.ardtt.app.ui.theme.ArdttRadius.Section
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
    ServerLights,
    Lift,
    Slide,
    Pulse,
    Heartbeat,
    GearHalfTurn,
    /** The folder opens and closes again, with a light pop. */
    FolderOpen,
    /** The three filter lines narrow one after another, like sifting. */
    Sift,
}

internal data class TabIconPose(
    val rotationX: Float = 0f,
    val rotationZ: Float = 0f,
    val translationXFraction: Float = 0f,
    val translationYFraction: Float = 0f,
    val scale: Float = 1f,
)

/**
 * Full roll around the horizontal axis. Halfway the key is upside down;
 * at the end it is home again. Not a spin in the plane of the icon.
 *
 * Drawn as a vector scale, not a 3D layer: a small glyph rotated in X is
 * clipped by a near camera and looks like dropped frames. The gear stays
 * smooth because it is a flat spin.
 */
internal const val KeyTurnDegrees = 360f

internal fun tabIconMotionFor(route: String): TabIconMotion = when (route) {
    "tunnel" -> TabIconMotion.KeyTurn
    "servers" -> TabIconMotion.ServerLights
    "profiles" -> TabIconMotion.FolderOpen
    "exceptions" -> TabIconMotion.Sift
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

/** One-way smoothstep, 0→1. Velocity is zero at the start and the end. */
internal fun tabIconTurn(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Orthographic height of the key. 1 upright, 0 edge-on, −1 upside down.
 * Same turn as [KeyTurnDegrees], sampled so neighbouring frames stay close.
 */
internal fun tabIconKeyScaleY(rotationX: Float): Float =
    cos(rotationX * (PI.toFloat() / 180f))

/**
 * Both server lamps, same curve as the diagnostic heartbeat: two soft blinks,
 * fully lit at the start, the middle, and the end.
 */
internal fun tabIconServerLightAlpha(progress: Float): Float =
    1f - tabIconHeartbeat(progress)

internal fun tabIconPose(motion: TabIconMotion, progress: Float): TabIconPose {
    val settle = tabIconSettle(progress)
    return when (motion) {
        TabIconMotion.GearHalfTurn -> TabIconPose(rotationZ = 180f * settle)
        TabIconMotion.KeyTurn -> TabIconPose(
            rotationX = KeyTurnDegrees * tabIconTurn(progress),
        )
        TabIconMotion.ServerLights -> TabIconPose()
        TabIconMotion.Lift -> TabIconPose(
            translationYFraction = -0.16f * settle,
            rotationZ = -8f * settle,
        )
        TabIconMotion.Slide -> TabIconPose(translationXFraction = 0.16f * settle)
        TabIconMotion.FolderOpen -> TabIconPose(scale = 1f + 0.10f * settle)
        TabIconMotion.Sift -> TabIconPose()
        TabIconMotion.Pulse -> TabIconPose(scale = 1f + 0.12f * settle)
        TabIconMotion.Heartbeat -> TabIconPose(scale = 1f + 0.14f * tabIconHeartbeat(progress))
    }
}

/**
 * Vector flip around the horizontal axis. The glyph is redrawn under the
 * scale, so the edge stays a smooth hairline instead of a torn texture.
 */
private fun Modifier.tunnelKeyFlip(rotationX: Float): Modifier = drawWithContent {
    val pivot = center
    val sy = tabIconKeyScaleY(rotationX)
    withTransform({
        scale(scaleX = 1f, scaleY = sy, pivot = pivot)
    }) {
        this@drawWithContent.drawContent()
    }
}

internal fun tabIconPoseAtRest(pose: TabIconPose): Boolean {
    fun wrappedRest(degrees: Float): Boolean {
        val wrapped = abs(degrees % 360f)
        return wrapped < 0.05f || abs(wrapped - 360f) < 0.05f
    }
    return wrappedRest(pose.rotationX) &&
        wrappedRest(pose.rotationZ) &&
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
    val shell = ArdttFloatingShell.shellColor()
    val selectedColor = colors.primary
    val unselectedColor = colors.onSurfaceVariant
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    val selectTab = rememberUpdatedState(onSelect)

    LaunchedEffect(selectedRoute) {
        pendingRoute = null
    }

    val outer = modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(
            horizontal = NavBarDefaults.OuterPadding,
            vertical = NavBarDefaults.TrackPadding,
        )
    // Selection droplet: follows the finger when dragged across the bar, then is pulled
    // into the nearest tab by the magnet and that tab opens. A plain tap works as before.
    val count = items.size
    val committedIndex = items.indexOfFirst { it.route == (pendingRoute ?: selectedRoute) }.coerceAtLeast(0)
    val selPos = remember { Animatable(committedIndex.toFloat()) }
    // Read through state: gesture handlers outlive recompositions.
    val magnetOn by androidx.compose.runtime.rememberUpdatedState(com.ardtt.app.ui.motion.LocalMagnetEnabled.current)
    var dragging by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val haptics = com.ardtt.app.ui.motion.LocalAppHaptics.current
    val range = (count - 1).coerceAtLeast(1).toFloat()
    LaunchedEffect(committedIndex, dragging) {
        if (!dragging) selPos.magnetTo(committedIndex.toFloat(), fullRange = range, enabled = magnetOn)
    }
    val currentItems by rememberUpdatedState(items)
    // The drag handler lives across recompositions: read the selected tab through state,
    // or it would compare against the tab that was selected when the bar first appeared.
    val currentSelected by rememberUpdatedState(selectedRoute)
    val dragToSelect = Modifier.pointerInput(count) {
        val pad = NavBarDefaults.TrackPadding.toPx()
        fun indexAt(x: Float): Float {
            val slot = (size.width - pad * 2) / count
            return ((x - pad) / slot - 0.5f).coerceIn(0f, (count - 1).toFloat())
        }
        fun follow(x: Float) {
            val raw = indexAt(x)
            val near = raw.roundToInt()
            if (near != hover) {
                hover = near
                haptics.perform(com.ardtt.app.ui.motion.Haptic.Tick)
            }
            scope.launch { selPos.snapTo(if (magnetOn) tabMagnet(raw) else raw) }
        }
        fun release() {
            val target = selPos.value.roundToInt().coerceIn(0, count - 1)
            val route = currentItems[target].route
            hover = -1
            if (route != currentSelected) {
                navTabPendingRoute(currentSelected, route)?.let { pendingRoute = it }
                selectTab.value(route)
            }
            scope.launch {
                selPos.magnetTo(target.toFloat(), fullRange = range, enabled = magnetOn, onContact = {
                    haptics.perform(com.ardtt.app.ui.motion.Haptic.Click)
                })
                dragging = false
            }
        }
        // Cancelled by the system or another gesture: nothing is selected, the droplet
        // goes back to the current tab.
        fun cancel() {
            hover = -1
            val home = currentItems.indexOfFirst { it.route == currentSelected }.coerceAtLeast(0)
            scope.launch {
                selPos.magnetTo(home.toFloat(), fullRange = range, enabled = magnetOn)
                dragging = false
            }
        }
        detectHorizontalDragGestures(
            onDragStart = { o -> dragging = true; hover = -1; follow(o.x) },
            onDragEnd = { release() },
            onDragCancel = { cancel() },
        ) { change, _ ->
            change.consume()
            follow(change.position.x)
        }
    }
    val shownIndex = if (dragging && hover >= 0) hover else -1
    // One label size for the whole bar: the smallest any label needs (large system font).
    val labelScales = remember { androidx.compose.runtime.mutableStateMapOf<String, Float>() }
    val labelCap = labelScales.values.minOrNull() ?: 1f

    val tabs: @Composable () -> Unit = {
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
                NavBarTab(
                    item = item,
                    selected = if (shownIndex >= 0) index == shownIndex else item.route == selectedRoute,
                    pending = shownIndex < 0 && item.route == pendingRoute,
                    emphasis = 0f,
                    selectedColor = selectedColor,
                    unselectedColor = unselectedColor,
                    onSelect = {
                        navTabPendingRoute(selectedRoute, item.route)?.let { pendingRoute = it }
                        selectTab.value(item.route)
                    },
                    labelCap = labelCap,
                    onLabelScale = { labelScales[item.route] = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    val backdrop = LocalGlassBackdrop.current
    if (backdrop != null && LiquidGlass.supported) {
        val dark = isDarkSurface()
        LiquidGlassPanel(
            backdrop = backdrop,
            cornerRadius = NavBarDefaults.GlassCorner,
            tint = colors.surface.copy(alpha = backdrop.veil(dark)),
            dark = dark,
            blur = backdrop.frost(),
            modifier = outer
                .shadow(18.dp, ArdttShapes.Section, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .height(ArdttSize.NavTrack)
                .then(dragToSelect),
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                // Lens in the lens: the selection is a small frosted glass lens inside the
                // panel. It magnifies what lies under it by about 5%: the page behind the
                // panel, frosted, and the selected tab, sharp. While dragged it swells.
                val lens by animateFloatAsState(
                    if (dragging) 1f else 0f,
                    spring(dampingRatio = 0.6f, stiffness = 420f),
                    label = "tabLens",
                )
                val panelLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
                val frostLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
                val panelGlass = remember(panelLayer) { com.ardtt.app.ui.components.surface.GlassBackdrop(panelLayer) }
                val veil = colors.surface.copy(alpha = backdrop.veil(dark))
                // The inner lens edge is where the light crosses two layers of glass: real
                // soda-lime glass shows a faint green there (iron traces). Kept faint.
                panelGlass.rim = GlassEdgeGreen
                // Panel picture for the lens: the page behind the panel under the veil, with
                // the tabs; the tabs are drawn as usual beneath the lens.
                Box(
                    Modifier
                        .matchParentSize()
                        .onGloballyPositioned { panelGlass.origin = it.positionInRoot() }
                        .drawWithContent {
                            val offset = panelGlass.origin - backdrop.origin
                            // Two layers for the lens: the page behind the panel, frosted (blur
                            // on its own layer), and the tabs sharp on top. The lens bends both,
                            // so the background is matte while the selected label stays crisp.
                            val r = 5.dp.toPx()
                            frostLayer.renderEffect = androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Clamp)
                            frostLayer.record {
                                translate(-offset.x, -offset.y) { drawLayer(backdrop.layer) }
                                drawRect(veil)
                            }
                            panelLayer.record {
                                drawLayer(frostLayer)
                                this@drawWithContent.drawContent()
                            }
                            drawContent()
                        },
                ) {
                    tabs()
                }
                val trackH = ArdttSize.NavTrack
                Box(
                    Modifier
                        .matchParentSize()
                        .layout { measurable, constraints ->
                            val pad = NavBarDefaults.TrackPadding.toPx()
                            val slot = (constraints.maxWidth - pad * 2) / count
                            val lh = (trackH.toPx() - 8.dp.toPx()) * (0.86f + 0.14f * lens)
                            // Nearly the slot width: the longest label must fit inside the flat middle.
                            // A bit wider than the slot (five tabs): the label must stay in the flat middle.
                            val lw = slot * (1.08f + 0.2f * lens)
                            val placeable = measurable.measure(
                                androidx.compose.ui.unit.Constraints.fixed(lw.roundToInt(), lh.roundToInt())
                            )
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                val cx = pad + slot * (selPos.value + 0.5f)
                                placeable.place((cx - lw / 2f).roundToInt(), ((constraints.maxHeight - lh) / 2f).roundToInt())
                            }
                        }
                        .shadow((2f + 4f * lens).dp, androidx.compose.foundation.shape.RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f)),
                ) {
                    LiquidGlassPanel(
                        backdrop = panelGlass,
                        // Moderate corners: with five tabs a pill-shaped lens bends the label ends.
                        cornerRadius = 20.dp,
                        // The frost is in the picture it bends (background only); a light haze.
                        tint = Color.White.copy(alpha = if (dark) 0.08f else 0.20f),
                        dark = dark,
                        // Narrow, shallow rim: the label sits close to the lower edge and must
                        // stay in the flat middle, not curl along the rim.
                        bezel = 6.dp,
                        depth = 2.dp,
                        zoom = 0.05f,
                        blur = 0.dp,
                        underlay = lerp(colors.surface, Color.White, if (dark) 0.06f else 0.5f),
                        fringe = 0.45f, dispersion = 0.2f,
                        modifier = Modifier.fillMaxSize(),
                    ) {}
                }
            }
        }
        return
    }

    Surface(
        shape = ArdttShapes.Section,
        color = shell,
        contentColor = ArdttSurface.contentColorOn(shell),
        border = ArdttFloatingShell.shellBorder(),
        tonalElevation = ArdttElevation.None,
        // A shadow graphics layer paints this translucent shell opaque for a frame.
        shadowElevation = ArdttElevation.None,
        modifier = outer,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ArdttSize.NavTrack)
                .then(dragToSelect),
        ) {
            SelectionDroplet(count = count, pos = selPos.value, velocity = selPos.velocity, dark = isDarkSurface(), glass = false)
            tabs()
        }
    }
}

/**
 * Magnet between tabs while dragging: near a tab the droplet is drawn in faster the
 * closer it gets and is caught on it; between tabs it follows the finger.
 */
internal fun tabMagnet(v: Float, reach: Float = 0.34f, capture: Float = 0.08f): Float {
    val stop = v.roundToInt().toFloat()
    val d = v - stop
    val x = kotlin.math.abs(d)
    if (x >= reach) return v
    if (x <= capture) return stop
    val u = (x - capture) / (reach - capture)
    return stop + kotlin.math.sign(d) * reach * kotlin.math.sqrt(u)
}

/**
 * Droplet under the selected tab. Moving fast it stretches like a drop of liquid,
 * then settles round. Glass mode: light glass body with a bright rim; plain mode: a soft tint.
 */
@Composable
private fun BoxScope.SelectionDroplet(count: Int, pos: Float, velocity: Float, dark: Boolean, glass: Boolean) {
    if (count <= 0) return
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.matchParentSize()) {
        val pad = NavBarDefaults.TrackPadding.toPx()
        val slot = (size.width - pad * 2) / count
        val stretch = (kotlin.math.abs(velocity) * 0.10f).coerceAtMost(0.45f)
        val w = slot * (0.86f + stretch)
        val h = size.height - 10.dp.toPx() - stretch * 6.dp.toPx()
        val cx = pad + slot * (pos + 0.5f)
        val left = cx - w / 2f
        val top = (size.height - h) / 2f
        val r = CornerRadius(h / 2f)
        if (!glass) {
            drawRoundRect(primary.copy(alpha = if (dark) 0.18f else 0.10f), Offset(left, top), Size(w, h), r)
            return@Canvas
        }
        drawRoundRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.55f), Color.White.copy(alpha = if (dark) 0.06f else 0.22f)),
                startY = top, endY = top + h,
            ),
            Offset(left, top), Size(w, h), r,
        )
        drawRoundRect(primary.copy(alpha = if (dark) 0.16f else 0.10f), Offset(left, top), Size(w, h), r)
        drawRoundRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.55f else 0.95f), Color.White.copy(alpha = 0.05f), primary.copy(alpha = 0.35f)),
                startY = top, endY = top + h,
            ),
            Offset(left, top), Size(w, h), r,
            style = Stroke(width = 1.2.dp.toPx()),
        )
    }
}

/**
 * Filter glyph (three centred lines, widest on top) whose lines narrow in turn from
 * top to bottom and spring back: things passing through the filter.
 */
@Composable
private fun SiftIcon(progress: Float, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val widths = floatArrayOf(18f, 12f, 6f)
        val ys = floatArrayOf(7f, 12f, 17f)
        for (i in 0..2) {
            // Each line has its own window inside the motion, staggered by 0.18.
            val local = ((progress - i * 0.18f) / 0.64f).coerceIn(0f, 1f)
            val squeeze = tabIconSettle(local)
            val w = widths[i] * (1f - 0.55f * squeeze) * u
            val cx = size.width / 2f
            drawLine(
                tint,
                Offset(cx - w / 2f, ys[i] * u), Offset(cx + w / 2f, ys[i] * u),
                strokeWidth = 2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
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
    labelCap: Float = 1f,
    onLabelScale: ((Float) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = com.ardtt.app.ui.motion.LocalAppHaptics.current
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
                    if (!selected) haptics.perform(com.ardtt.app.ui.motion.Haptic.Click)
                    if (!iconProgress.isRunning) iconPlay++
                    onSelect()
                },
            )
            .semantics { this.selected = selected },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            val glyph = Modifier.size(ArdttSize.Icon)
            if (iconMotion == TabIconMotion.Sift) {
                SiftIcon(iconProgress.value, paint.color, glyph)
            } else if (iconMotion == TabIconMotion.FolderOpen) {
                // Open for the middle of the motion, closed at both ends.
                val open = iconProgress.value in 0.12f..0.78f
                Icon(
                    imageVector = if (open) Icons.Outlined.FolderOpen else item.icon,
                    contentDescription = null,
                    modifier = glyph.graphicsLayer {
                        scaleX = iconPose.scale
                        scaleY = iconPose.scale
                    },
                    tint = paint.color,
                )
            } else if (iconMotion == TabIconMotion.ServerLights) {
                ArdttServersTabIcon(
                    lightAlpha = tabIconServerLightAlpha(iconProgress.value),
                    tint = paint.color,
                    modifier = glyph,
                )
            } else {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = if (iconMotion == TabIconMotion.KeyTurn) {
                        glyph.tunnelKeyFlip(iconPose.rotationX)
                    } else {
                        glyph.graphicsLayer {
                            rotationZ = iconPose.rotationZ
                            translationX = iconPose.translationXFraction * size.width
                            translationY = iconPose.translationYFraction * size.height
                            scaleX = iconPose.scale
                            scaleY = iconPose.scale
                        }
                    },
                    tint = paint.color,
                )
            }
            if (item.dot && item.badgeCount == 0) {
                Badge(modifier = Modifier.offset(x = ArdttSpacing.Tiny, y = -ArdttSpacing.Tiny))
            }
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
        // Over clear glass the page shows right behind the label: a soft halo of the
        // background colour keeps it legible at any transparency.
        val glass = LocalGlassBackdrop.current
        val halo = if (glass != null && glass.veil(isDarkSurface()) < 0.25f) {
            androidx.compose.ui.graphics.Shadow(MaterialTheme.colorScheme.background, blurRadius = with(androidx.compose.ui.platform.LocalDensity.current) { 7.dp.toPx() })
        } else null
        // One line; with a large system font the label shrinks instead of breaking mid-word.
        // On glass the label must fit the flat middle of the selection lens (narrow rim,
        // 5% zoom), or the lens edge clips it; it shrinks instead.
        com.ardtt.app.ui.components.control.FitText(
            text = item.label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (glass != null && selected) 6.dp else 1.dp),
            style = ArdttNavigationLabelStyle.copy(
                shadow = halo,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            fontWeight = if (paint.bold) FontWeight.SemiBold else FontWeight.Normal,
            color = paint.color.copy(alpha = paint.labelAlpha),
            minScale = 0.55f,
            scaleCap = labelCap,
            onScale = onLabelScale,
        )
    }
}
