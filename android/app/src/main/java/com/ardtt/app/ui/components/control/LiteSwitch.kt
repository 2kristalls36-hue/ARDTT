package com.ardtt.app.ui.components.control

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.components.surface.GlassBackdrop
import com.ardtt.app.ui.components.surface.LiquidGlassPanel
import com.ardtt.app.ui.components.surface.LocalGlassBackdrop
import com.ardtt.app.ui.motion.magnetTo
import com.ardtt.app.ui.theme.isDarkSurface
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Switch of the app. With liquid glass off it is the standard Material switch. With glass
 * on the track is a recessed groove and the thumb a round white glass disc lying in it:
 * it slides along and bends the groove under its rim, lifting a little while held or
 * moving. The disc can be dragged; on release the magnet pulls it to the nearest side. Vibration is left to
 * the caller, as with the Material switch.
 *
 * [onCheckedChange] null: display only (the row around it handles the tap); the lens
 * still shows while the thumb travels.
 */
@Composable
fun LiteSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalGlassBackdrop.current == null) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, modifier = modifier)
        return
    }
    GlassSwitch(checked, onCheckedChange, modifier, enabled)
}

@Composable
private fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    // Read through state: gesture handlers outlive recompositions.
    val magnetOn by androidx.compose.runtime.rememberUpdatedState(com.ardtt.app.ui.motion.LocalMagnetEnabled.current)
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(checked, dragging) {
        if (!dragging) pos.magnetTo(if (checked) 1f else 0f, enabled = magnetOn)
    }
    val moving = pos.isRunning && !(pos.value == 0f || pos.value == 1f)
    // The disc lifts a little while held or travelling.
    val lift by animateFloatAsState(
        if (enabled && (pressed || dragging || moving)) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "switchLift",
    )
    val onTrack = if (dark) Color(0xFF4ADE80) else Color(0xFF34C759)
    val offTrack = colors.outline.copy(alpha = if (dark) 0.45f else 0.30f)
    val surface = colors.surface
    val current by rememberUpdatedState(checked)
    val change by rememberUpdatedState(onCheckedChange)

    val layer = rememberGraphicsLayer()
    val glass = remember(layer) { GlassBackdrop(layer) }
    val w = 52.dp
    val h = 32.dp

    Box(
        modifier
            .size(w, h)
            // Fade without an offscreen buffer: one would clip the glass thumb where it
            // reaches past these bounds (at the end stops).
            .graphicsLayer {
                this.alpha = if (enabled) 1f else 0.45f
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }
            .onGloballyPositioned { glass.origin = it.positionInRoot() }
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "Включено" else "Выключено"
            }
            .then(
                if (onCheckedChange == null || !enabled) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                            onTap = { change?.invoke(!current) },
                        )
                    }
                    .pointerInput(Unit) {
                        val travel = size.width - size.height.toFloat()
                        var start = 0f
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true; start = pos.value; dx = 0f },
                            onDragEnd = {
                                val on = pos.value >= 0.5f
                                scope.launch {
                                    pos.magnetTo(if (on) 1f else 0f, enabled = magnetOn)
                                    dragging = false
                                }
                                if (on != current) change?.invoke(on)
                            },
                            onDragCancel = { dragging = false },
                        ) { c, amount ->
                            c.consume()
                            dx += amount
                            scope.launch { pos.snapTo((start + dx / travel).coerceIn(0f, 1f)) }
                        }
                    }
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val p = pos.value
            val trackColor = lerp(offTrack, onTrack, p)
            val radius = CornerRadius(size.height / 2f)
            // Recessed groove: the colour sits lower than the page, shaded under the top
            // edge and lit along the bottom one.
            val drawTrack: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                drawRoundRect(trackColor, Offset.Zero, size, radius)
                drawRoundRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = if (dark) 0.45f else 0.22f),
                        0.35f to Color.Black.copy(alpha = 0.04f),
                        0.75f to Color.Transparent,
                        1f to Color.White.copy(alpha = if (dark) 0.10f else 0.35f),
                    ),
                    Offset.Zero, size, radius,
                )
                drawRoundRect(
                    Color.Black.copy(alpha = if (dark) 0.35f else 0.12f), Offset.Zero, size, radius,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                )
            }
            layer.record(Density(density, fontScale), layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
                drawRect(surface)
                drawTrack()
            }
            drawTrack()
        }
        // Round white glass disc lying in the groove; it slides and bends the groove under it.
        val inset = 3.dp
        val d = h - inset * 2
        val dd = d * (1f + 0.14f * lift)
        Box(
            Modifier
                // Placed by hand: when lifted the disc is larger than its slot.
                .layout { measurable, constraints ->
                    val px = dd.roundToPx()
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(px, px))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val travel = w.toPx() - h.toPx()
                        val cx = h.toPx() / 2f + travel * pos.value
                        placeable.place((cx - px / 2f).roundToInt(), ((h.toPx() - px) / 2f).roundToInt())
                    }
                }
                .shadow((2f + 5f * lift).dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f)),
        ) {
            LiquidGlassPanel(
                backdrop = glass,
                cornerRadius = dd / 2,
                tint = Color.White.copy(alpha = if (dark) 0.30f else 0.42f),
                dark = dark,
                bezel = dd * 0.24f,
                depth = dd * 0.4f,
                zoom = 0.06f,
                blur = 0.dp,
                underlay = surface,
                fringe = 0.35f, steepRim = true, dispersion = 0.35f,
                modifier = Modifier.fillMaxSize(),
            ) {}
        }
    }
}
