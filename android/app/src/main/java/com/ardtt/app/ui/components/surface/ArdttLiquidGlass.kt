package com.ardtt.app.ui.components.surface

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Refraction for translucent chrome on Android 13+ (RuntimeShader).
 *
 * One pass records the tree with the glass plates left out and flattens it to
 * a bitmap, so a plate can bend those pixels without sampling a layer that is
 * still recording. The next pass draws the plates on top. Older releases keep
 * the flat translucent fill. Sheets, dialogs, and the header are not plates.
 */
internal object ArdttLiquidGlass {
    /** Normalized radius where the rim bend begins. */
    const val EdgeInner = 0.45f

    /** How far past [EdgeInner] the bend reaches full strength (radius 1.05). */
    const val EdgeSpan = 0.60f

    /**
     * Shader multiplier. At the rim, where the normalized offset is about 0.5,
     * the sample moves by roughly half of this, in pixels.
     */
    const val BendPx = 72f

    /** Extra pixels recorded around a plate so the rim can sample outside it. */
    const val SampleMarginPx = 64f

    /** Shell plates: frost in the middle, clearer at the rim. RGB comes from the tint. */
    const val ShellCenterAlpha = 0.62f
    const val ShellEdgeAlpha = 0.24f

    /** Hue-locked buttons keep [tint alpha] in the middle and this fraction at the rim. */
    const val HueEdgeScale = 0.525f

    /** Share of the half-diagonal that stays at the center frost. */
    const val FrostKnee = 0.45f

    const val SheenAlpha = 0.16f
    const val SheenReach = 0.42f

    private const val LogTag = "ArdttLiquidGlass"
    private var loggedSnapshotFailure = false

    fun logSnapshotFailure(error: Throwable) {
        if (loggedSnapshotFailure) return
        loggedSnapshotFailure = true
        Log.w(LogTag, "backdrop snapshot failed", error)
    }
}

internal class ArdttLiquidGlassSession(
    val supported: Boolean,
) {
    var capturing: Boolean = false
    var root: LayoutCoordinates? = null
    var frame: ImageBitmap? = null
    private var latest: Bitmap? = null
    private var previous: Bitmap? = null

    fun publish(bitmap: Bitmap) {
        previous?.let { old ->
            if (!old.isRecycled) old.recycle()
        }
        previous = latest
        latest = bitmap
        frame = bitmap.asImageBitmap()
    }

    fun clear() {
        frame = null
        root = null
        latest?.let { if (!it.isRecycled) it.recycle() }
        previous?.let { if (!it.isRecycled) it.recycle() }
        latest = null
        previous = null
    }
}

internal val LocalArdttLiquidGlass = staticCompositionLocalOf {
    ArdttLiquidGlassSession(supported = false)
}

internal fun liquidGlassSupported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 33

/** Smoothstep weight: 0 at and inside [ArdttLiquidGlass.EdgeInner], 1 at the outer rim. */
internal fun liquidGlassEdgeWeight(normalizedRadius: Float): Float {
    val t = ((normalizedRadius - ArdttLiquidGlass.EdgeInner) / ArdttLiquidGlass.EdgeSpan)
        .coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Pixel offset added to a sample. [normalizedFromCenter] is `coord / size - 0.5`
 * in the plate's own space (before the capture margin).
 */
internal fun liquidGlassSampleOffset(normalizedFromCenter: Offset): Offset {
    val radius = hypot(normalizedFromCenter.x.toDouble(), normalizedFromCenter.y.toDouble())
        .toFloat() * 2f
    val edge = liquidGlassEdgeWeight(radius)
    return Offset(
        x = normalizedFromCenter.x * edge * ArdttLiquidGlass.BendPx,
        y = normalizedFromCenter.y * edge * ArdttLiquidGlass.BendPx,
    )
}

internal data class LiquidGlassFrost(
    val red: Float,
    val green: Float,
    val blue: Float,
    val centerAlpha: Float,
    val edgeAlpha: Float,
)

/**
 * Frost laid over the refraction. Hue-locked buttons keep the tint's alpha in
 * the center (80% for an enabled glass CTA). Shell chrome ignores the tint
 * alpha, which is the flat-fill opacity, and uses the shell pair instead.
 */
internal fun liquidGlassFrost(tint: Color, hueLocked: Boolean): LiquidGlassFrost {
    val center = if (hueLocked) tint.alpha else ArdttLiquidGlass.ShellCenterAlpha
    val edge = if (hueLocked) {
        center * ArdttLiquidGlass.HueEdgeScale
    } else {
        ArdttLiquidGlass.ShellEdgeAlpha
    }
    return LiquidGlassFrost(
        red = tint.red,
        green = tint.green,
        blue = tint.blue,
        centerAlpha = center,
        edgeAlpha = edge,
    )
}

/** Half the diagonal, so a corner of the plate is the clear rim. */
internal fun liquidGlassFrostRadius(width: Float, height: Float): Float =
    hypot(width.toDouble(), height.toDouble()).toFloat() * 0.5f

/** 0 = center of the plate, 1 = the corner along [liquidGlassFrostRadius]. */
internal fun liquidGlassFrostAlpha(center: Float, edge: Float, distanceFraction: Float): Float {
    val span = (1f - ArdttLiquidGlass.FrostKnee).coerceAtLeast(0.001f)
    val t = ((distanceFraction - ArdttLiquidGlass.FrostKnee) / span).coerceIn(0f, 1f)
    return center + (edge - center) * t
}

internal fun liquidGlassOrigin(root: LayoutCoordinates?, glass: LayoutCoordinates?): Offset? {
    if (root == null || glass == null) return null
    if (!root.isAttached || !glass.isAttached) return null
    return root.localPositionOf(glass, Offset.Zero)
}

internal fun liquidGlassShapePath(
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): Path {
    return when (val outline = shape.createOutline(size, layoutDirection, density)) {
        is Outline.Generic -> outline.path
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    }
}

/** AGSL body. Uniform names are the contract with [android.graphics.RuntimeShader]. */
internal fun liquidGlassAgsl(): String {
    val inner = "%.2f".format(Locale.US, ArdttLiquidGlass.EdgeInner)
    val span = "%.2f".format(Locale.US, ArdttLiquidGlass.EdgeSpan)
    return """
        uniform shader contents;
        uniform float2 size;
        uniform float margin;
        uniform float bend;
        half4 main(float2 coord) {
            float2 local = coord - float2(margin, margin);
            float2 p = local / size;
            float2 c = p - float2(0.5, 0.5);
            float r = length(c) * 2.0;
            float t = clamp((r - $inner) / $span, 0.0, 1.0);
            float edge = t * t * (3.0 - 2.0 * t);
            return contents.eval(coord + c * edge * bend);
        }
    """.trimIndent()
}

/**
 * Draws the flat tint the plate used before refraction. Android 12 and older
 * stay on this path; a missing snapshot does too.
 */
internal fun DrawScope.drawLiquidGlassPlate(
    shape: Shape,
    tint: Color,
    density: Density,
    layoutDirection: LayoutDirection,
) {
    drawPath(liquidGlassShapePath(shape, size, layoutDirection, density), tint)
}

internal fun DrawScope.drawLiquidGlassFrost(
    shape: Shape,
    tint: Color,
    hueLocked: Boolean,
    density: Density,
    layoutDirection: LayoutDirection,
) {
    val frost = liquidGlassFrost(tint, hueLocked)
    val rgb = Color(frost.red, frost.green, frost.blue, 1f)
    val radius = liquidGlassFrostRadius(size.width, size.height)
    val path = liquidGlassShapePath(shape, size, layoutDirection, density)
    clipPath(path) {
        if (radius > 0f) {
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to rgb.copy(alpha = frost.centerAlpha),
                        ArdttLiquidGlass.FrostKnee to rgb.copy(alpha = frost.centerAlpha),
                        1f to rgb.copy(alpha = frost.edgeAlpha),
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = radius,
                ),
            )
        }
        val sheenHeight = size.height * ArdttLiquidGlass.SheenReach
        if (sheenHeight > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = ArdttLiquidGlass.SheenAlpha),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = sheenHeight,
                ),
                size = Size(size.width, sheenHeight),
            )
        }
    }
}

/**
 * Host for [liquidGlass]. Records the tree once with plates omitted, flattens
 * that into a bitmap, then draws the tree again so the plates can bend it.
 */
@Composable
internal fun Modifier.liquidGlassBackdrop(): Modifier {
    val session = LocalArdttLiquidGlass.current
    return if (session.supported) {
        liquidGlassBackdropRecording(session)
    } else {
        this
    }
}

@Composable
private fun Modifier.liquidGlassBackdropRecording(session: ArdttLiquidGlassSession): Modifier {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val layer = rememberGraphicsLayer()
    DisposableEffect(session) {
        onDispose { session.clear() }
    }
    return this
        .onGloballyPositioned { session.root = it }
        .drawWithContent {
            val frameDraw = this
            val widthPx = ceil(size.width).toInt().coerceAtLeast(1)
            val heightPx = ceil(size.height).toInt().coerceAtLeast(1)
            session.capturing = true
            var recorded = false
            try {
                layer.record(
                    density = density,
                    layoutDirection = layoutDirection,
                    size = IntSize(widthPx, heightPx),
                ) {
                    frameDraw.drawContent()
                }
                recorded = true
            } finally {
                session.capturing = false
            }
            if (recorded) {
                rasterizeLiquidGlass(layer, widthPx, heightPx, density, layoutDirection)
                    ?.let(session::publish)
            }
            drawContent()
        }
}

/**
 * Copies the recorded tree into a bitmap. Drawing the layer itself later would
 * replay render nodes that are recording again on the present pass.
 */
internal fun rasterizeLiquidGlass(
    layer: GraphicsLayer,
    widthPx: Int,
    heightPx: Int,
    density: Density,
    layoutDirection: LayoutDirection,
): Bitmap? {
    return try {
        val picture = object : android.graphics.Picture() {
            override fun getWidth(): Int = widthPx
            override fun getHeight(): Int = heightPx
            override fun requiresHardwareAcceleration(): Boolean = true
            override fun draw(canvas: android.graphics.Canvas) {
                CanvasDrawScope().draw(
                    density = density,
                    layoutDirection = layoutDirection,
                    canvas = androidx.compose.ui.graphics.Canvas(canvas),
                    size = Size(widthPx.toFloat(), heightPx.toFloat()),
                ) {
                    drawLayer(layer)
                }
            }
        }
        Bitmap.createBitmap(picture)
    } catch (error: Throwable) {
        ArdttLiquidGlass.logSnapshotFailure(error)
        null
    }
}

@Composable
internal fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color,
    hueLocked: Boolean,
): Modifier {
    return if (Build.VERSION.SDK_INT >= 33) {
        liquidGlassRefractive(shape, tint, hueLocked)
    } else {
        liquidGlassFlat(shape, tint)
    }
}

@Composable
private fun Modifier.liquidGlassFlat(shape: Shape, tint: Color): Modifier {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    return this.drawBehind {
        drawLiquidGlassPlate(shape, tint, density, layoutDirection)
    }
}
