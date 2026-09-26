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
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Refraction for translucent chrome on Android 13+ (RuntimeShader).
 *
 * One pass records the tree with the glass plates left out and flattens it to
 * a bitmap, so a plate can bend those pixels without sampling a layer that is
 * still recording. The next pass draws the plates on top. The lens is a dome:
 * text in the middle is magnified, and a narrow bevel at the rim squeezes the
 * backdrop along the outline, with a thin highlight on that bevel only. Older
 * releases keep the flat translucent fill. Sheets, dialogs, and the header
 * are not plates.
 */
internal object ArdttLiquidGlass {
    /**
     * Magnification once the sample is inside the bevel. 0.55 pulls a point
     * halfway to the rim back toward the center by about a quarter of that
     * distance, so letters behind the plate grow instead of sitting flat.
     */
    const val LensZoom = 0.55f

    /**
     * Peak bevel shift, in pixels, before the height derivative (max 1.5).
     * The bright rim of the glass lives in this band.
     */
    const val BendPx = 36f

    /** Bevel width as a fraction of the plate's shorter side. */
    const val BevelFraction = 0.28f

    /** Red/blue split at the peak of the bevel, in pixels along the outward normal. */
    const val ChromaPx = 5f

    /** Backdrop blur before the bend, in pixels. Low enough that letter edges survive. */
    const val BlurPx = 2.5f

    /**
     * Extra pixels around a plate. A wide bar magnifies by hundreds of pixels
     * toward its ends; a short margin clamps that read to empty pixels and the
     * rim looks like a flat tint.
     */
    const val SampleMarginPx = 280f

    /**
     * Shell plates stay clear. The dome has to read through the middle, so the
     * wash is only a hint of the shell color.
     */
    const val ShellCenterAlpha = 0.07f
    const val ShellEdgeAlpha = 0.02f

    /**
     * Hue-locked buttons keep their own RGB, as a thin wash. A light tint at
     * a high alpha paints a white disk in the middle and hides the refraction.
     * A dimmed tint scales both alphas down.
     */
    const val HueCenterAlpha = 0.10f
    const val HueEdgeAlpha = 0.03f

    /** Share of the frost radius that stays at the center wash. */
    const val FrostKnee = 0.22f

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
    var frame: Bitmap? = null
    private var latest: Bitmap? = null
    private var previous: Bitmap? = null

    fun publish(bitmap: Bitmap) {
        previous?.let { old ->
            if (!old.isRecycled) old.recycle()
        }
        previous = latest
        latest = bitmap
        frame = bitmap
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

/**
 * Bevel response for a point [inside] pixels in from the outline.
 * [spread] is how far the bevel reaches. The first value rises from 0 on the
 * outline to 1 once the bevel is passed (dome weight). The second is the
 * height slope, 0 on the outline and past the bevel, peaking at 1.5 halfway
 * across it.
 */
internal fun liquidGlassBevel(inside: Float, spread: Float): Pair<Float, Float> {
    val safe = spread.coerceAtLeast(1f)
    val u = (inside / safe).coerceIn(0f, 1f)
    val dome = u * u * (3f - 2f * u)
    val slope = 6f * u * (1f - u)
    return dome to slope
}

/** Signed distance of a rounded box. [point] and [halfSize] are pixels from the center. */
internal fun liquidGlassRoundBoxDistance(point: Offset, halfSize: Offset, corner: Float): Float {
    val rad = corner.coerceIn(0f, min(halfSize.x, halfSize.y))
    val qx = abs(point.x) - halfSize.x + rad
    val qy = abs(point.y) - halfSize.y + rad
    val outside = hypot(max(qx, 0f).toDouble(), max(qy, 0f).toDouble()).toFloat()
    val inside = min(max(qx, qy), 0f)
    return outside + inside - rad
}

/** Outward normal of [liquidGlassRoundBoxDistance]. Zero at the exact center. */
internal fun liquidGlassRoundBoxNormal(point: Offset, halfSize: Offset, corner: Float): Offset {
    val rad = corner.coerceIn(0f, min(halfSize.x, halfSize.y))
    val qx = abs(point.x) - halfSize.x + rad
    val qy = abs(point.y) - halfSize.y + rad
    val gx: Float
    val gy: Float
    if (qx > 0f && qy > 0f) {
        val len = hypot(qx.toDouble(), qy.toDouble()).toFloat().coerceAtLeast(0.001f)
        gx = qx / len
        gy = qy / len
    } else if (qx > qy) {
        gx = 1f
        gy = 0f
    } else {
        gx = 0f
        gy = 1f
    }
    val sx = when {
        point.x > 0f -> 1f
        point.x < 0f -> -1f
        else -> 0f
    }
    val sy = when {
        point.y > 0f -> 1f
        point.y < 0f -> -1f
        else -> 0f
    }
    return Offset(gx * sx, gy * sy)
}

/**
 * Pixel shift of the backdrop sample. The shader reads `coord - shift`, so a
 * positive x pulls content from the left. [local] is the plate point in pixels,
 * origin at the top-left, before the capture margin.
 */
internal fun liquidGlassSampleShift(local: Offset, size: Size, corner: Float): Offset {
    val point = Offset(local.x - size.width * 0.5f, local.y - size.height * 0.5f)
    val half = Offset(size.width * 0.5f, size.height * 0.5f)
    val rad = corner.coerceIn(0f, min(size.width, size.height) * 0.5f)
    val distance = liquidGlassRoundBoxDistance(point, half, rad)
    val normal = liquidGlassRoundBoxNormal(point, half, rad)
    val spread = max(min(size.width, size.height) * ArdttLiquidGlass.BevelFraction, 1f)
    val (dome, slope) = liquidGlassBevel(-distance, spread)
    val zoom = ArdttLiquidGlass.LensZoom * dome
    val bend = slope * ArdttLiquidGlass.BendPx
    return Offset(
        x = point.x * zoom + normal.x * bend,
        y = point.y * zoom + normal.y * bend,
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
 * Wash laid over the refraction. Hue-locked buttons keep the tint's RGB and
 * scale the wash by how strong that tint is, so a disabled button thins out.
 * Shell chrome ignores the flat-fill alpha and uses the shell pair.
 */
internal fun liquidGlassFrost(tint: Color, hueLocked: Boolean): LiquidGlassFrost {
    val presence = if (hueLocked) {
        (tint.alpha / ArdttFloatingShell.ButtonAlpha).coerceIn(0f, 1f)
    } else {
        1f
    }
    val center = (if (hueLocked) {
        ArdttLiquidGlass.HueCenterAlpha
    } else {
        ArdttLiquidGlass.ShellCenterAlpha
    }) * presence
    val edge = (if (hueLocked) {
        ArdttLiquidGlass.HueEdgeAlpha
    } else {
        ArdttLiquidGlass.ShellEdgeAlpha
    }) * presence
    return LiquidGlassFrost(
        red = tint.red,
        green = tint.green,
        blue = tint.blue,
        centerAlpha = center,
        edgeAlpha = edge,
    )
}

/**
 * Half the shorter side. On a wide pill the top and bottom edges reach the
 * clear rim; a half-diagonal left those edges inside the dense center.
 */
internal fun liquidGlassFrostRadius(width: Float, height: Float): Float =
    min(width, height) * 0.5f

/** Corner radius of [shape] in pixels. A circle uses half its shorter side. */
internal fun liquidGlassCornerRadius(
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): Float {
    val outline = shape.createOutline(size, layoutDirection, density)
    return when (outline) {
        is Outline.Rounded -> max(
            outline.roundRect.topLeftCornerRadius.x,
            outline.roundRect.topLeftCornerRadius.y,
        )
        else -> min(size.width, size.height) * 0.5f
    }
}

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
    val zoom = "%.2f".format(Locale.US, ArdttLiquidGlass.LensZoom)
    val bevel = "%.2f".format(Locale.US, ArdttLiquidGlass.BevelFraction)
    return """
        uniform shader contents;
        uniform float2 size;
        uniform float margin;
        uniform float bend;
        uniform float radius;
        uniform float chroma;
        float sdRoundBox(float2 p, float2 b, float rad) {
            float2 q = abs(p) - b + float2(rad, rad);
            return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - rad;
        }
        float2 sdRoundBoxNormal(float2 p, float2 b, float rad) {
            float2 q = abs(p) - b + float2(rad, rad);
            float2 g;
            if (q.x > 0.0 && q.y > 0.0) {
                g = q / max(length(q), 0.001);
            } else if (q.x > q.y) {
                g = float2(1.0, 0.0);
            } else {
                g = float2(0.0, 1.0);
            }
            return g * sign(p);
        }
        half4 main(float2 coord) {
            float2 local = coord - float2(margin, margin);
            float2 p = local - size * 0.5;
            float rad = clamp(radius, 0.0, min(size.x, size.y) * 0.5);
            float2 box = size * 0.5;
            float sdf = sdRoundBox(p, box, rad);
            float2 n = sdRoundBoxNormal(p, box, rad);
            float spread = max(min(size.x, size.y) * $bevel, 1.0);
            float u = clamp(max(-sdf, 0.0) / spread, 0.0, 1.0);
            float dome = u * u * (3.0 - 2.0 * u);
            float dh = 6.0 * u * (1.0 - u);
            float2 delta = p * ($zoom * dome) + n * dh * bend;
            float fringe = dh * chroma;
            half4 mid = contents.eval(coord - delta);
            half4 hi = contents.eval(coord - delta + n * fringe);
            half4 lo = contents.eval(coord - delta - n * fringe);
            float shine = dh / 1.5;
            shine = shine * shine;
            half3 rgb = half3(hi.r, mid.g, lo.b) + half3(shine * 0.55);
            return half4(min(rgb, half3(1.0)), mid.a);
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
