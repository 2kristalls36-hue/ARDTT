package com.ardtt.app.ui.components.surface

import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil

/**
 * Android 13+ plate. Kept in its own class so API 28 never loads [RuntimeShader].
 */
private class LiquidGlassShaders {
    private val shader = RuntimeShader(liquidGlassAgsl())
    private var effectWidth = Float.NaN
    private var effectHeight = Float.NaN
    private var effect: RenderEffect? = null

    fun effect(width: Float, height: Float): RenderEffect {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("margin", ArdttLiquidGlass.SampleMarginPx)
        shader.setFloatUniform("bend", ArdttLiquidGlass.BendPx)
        if (effect == null || effectWidth != width || effectHeight != height) {
            effectWidth = width
            effectHeight = height
            effect = RenderEffect.createRuntimeShaderEffect(shader, "contents")
        }
        return effect!!
    }
}

@Composable
internal fun Modifier.liquidGlassRefractive(
    shape: Shape,
    tint: Color,
    hueLocked: Boolean,
): Modifier {
    val session = LocalArdttLiquidGlass.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val lens = remember { RenderNode("ardtt-liquid-glass") }
    val shaders = remember { LiquidGlassShaders() }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { coordinates = it }
        .drawWithContent {
            if (session.capturing) return@drawWithContent
            val frame = session.frame
            val origin = liquidGlassOrigin(session.root, coordinates)
            if (frame != null && origin != null && size.width > 1f && size.height > 1f) {
                drawLiquidGlassLens(
                    shaders = shaders,
                    lens = lens,
                    frame = frame,
                    origin = origin,
                    shape = shape,
                    density = density,
                    layoutDirection = layoutDirection,
                )
            }
            drawLiquidGlassFrost(shape, tint, hueLocked, density, layoutDirection)
            drawContent()
        }
}

private fun DrawScope.drawLiquidGlassLens(
    shaders: LiquidGlassShaders,
    lens: RenderNode,
    frame: ImageBitmap,
    origin: Offset,
    shape: Shape,
    density: Density,
    layoutDirection: LayoutDirection,
) {
    val margin = ArdttLiquidGlass.SampleMarginPx
    val lensWidth = ceil(size.width + margin * 2f).toInt().coerceAtLeast(1)
    val lensHeight = ceil(size.height + margin * 2f).toInt().coerceAtLeast(1)
    lens.setPosition(0, 0, lensWidth, lensHeight)
    // The effect runs on the whole node, including the margin. The shape clip
    // below only masks the result, so the rim can still sample outside the plate.
    lens.setRenderEffect(shaders.effect(size.width, size.height))
    val recording = lens.beginRecording()
    try {
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = layoutDirection,
            canvas = androidx.compose.ui.graphics.Canvas(recording),
            size = Size(lensWidth.toFloat(), lensHeight.toFloat()),
        ) {
            drawImage(
                image = frame,
                topLeft = Offset(margin - origin.x, margin - origin.y),
            )
        }
    } finally {
        lens.endRecording()
    }
    val plate = liquidGlassShapePath(shape, size, layoutDirection, density)
    clipPath(plate) {
        val native = drawContext.canvas.nativeCanvas
        native.save()
        native.translate(-margin, -margin)
        native.drawRenderNode(lens)
        native.restore()
    }
}
