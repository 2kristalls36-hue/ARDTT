package com.ardtt.app.ui.components.surface

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttAlpha
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttLiquidGlassTest {
    @Test
    fun bevelIsFlatPastTheRimAndSteepestHalfwayAcrossIt() {
        val spread = 20f
        val (edgeDome, edgeSlope) = liquidGlassBevel(0f, spread)
        assertEquals(0f, edgeDome, 0.001f)
        assertEquals(0f, edgeSlope, 0.001f)
        val (innerDome, innerSlope) = liquidGlassBevel(spread, spread)
        assertEquals(1f, innerDome, 0.001f)
        assertEquals(0f, innerSlope, 0.001f)
        val (midDome, midSlope) = liquidGlassBevel(spread / 2f, spread)
        assertTrue(midDome > 0.4f)
        assertTrue(midDome < 0.6f)
        assertEquals(1.5f, midSlope, 0.001f)
        assertTrue(midSlope > edgeSlope)
        assertTrue(midSlope > innerSlope)
    }

    @Test
    fun textUnderThePlateBendsBeforeTheRim() {
        val size = Size(280f, 64f)
        val corner = 32f
        val center = liquidGlassSampleShift(Offset(140f, 32f), size, corner)
        assertEquals(0f, center.x, 0.5f)
        assertEquals(0f, center.y, 0.5f)

        val upper = liquidGlassSampleShift(Offset(140f, 8f), size, corner)
        val lower = liquidGlassSampleShift(Offset(140f, 24f), size, corner)
        assertTrue(upper.y < -8f)
        assertTrue(abs(upper.y - lower.y) > 4f)

        val side = liquidGlassSampleShift(Offset(270f, 32f), size, corner)
        assertTrue(side.x > 24f)
        assertTrue(abs(side.x) + abs(side.y) < ArdttLiquidGlass.SampleMarginPx + size.width)
    }

    @Test
    fun blurAndFringeStayInsideTheCaptureMargin() {
        assertTrue(ArdttLiquidGlass.BlurPx + ArdttLiquidGlass.ChromaPx < ArdttLiquidGlass.SampleMarginPx)
        assertTrue(ArdttLiquidGlass.LensZoom > 0f)
        assertTrue(ArdttLiquidGlass.BendPx > 24f)
    }

    @Test
    fun hueLockedFrostKeepsTheButtonHueAndThinsTheWash() {
        val red = Color(0xFFBA1A1A).copy(alpha = ArdttFloatingShell.ButtonAlpha)
        val frost = liquidGlassFrost(red, hueLocked = true)
        assertEquals(ArdttLiquidGlass.HueCenterAlpha, frost.centerAlpha, 0.001f)
        assertEquals(ArdttLiquidGlass.HueEdgeAlpha, frost.edgeAlpha, 0.001f)
        assertTrue(frost.centerAlpha < ArdttFloatingShell.ButtonAlpha)
        assertTrue(frost.edgeAlpha < frost.centerAlpha)
        assertEquals(red.red, frost.red, 0.001f)
        assertEquals(red.green, frost.green, 0.001f)
        assertEquals(red.blue, frost.blue, 0.001f)

        val disabled = red.copy(alpha = ArdttFloatingShell.ButtonAlpha * ArdttAlpha.DisabledContainer)
        val faded = liquidGlassFrost(disabled, hueLocked = true)
        assertEquals(ArdttLiquidGlass.HueCenterAlpha * ArdttAlpha.DisabledContainer, faded.centerAlpha, 0.001f)
        assertEquals(red.red, faded.red, 0.001f)
    }

    @Test
    fun shellFrostIgnoresTheFlatFillAlpha() {
        val light = liquidGlassFrost(Color.White.copy(alpha = 0.96f), hueLocked = false)
        val dark = liquidGlassFrost(Color(0xFF16202C).copy(alpha = 0.88f), hueLocked = false)
        assertEquals(ArdttLiquidGlass.ShellCenterAlpha, light.centerAlpha, 0.001f)
        assertEquals(ArdttLiquidGlass.ShellEdgeAlpha, light.edgeAlpha, 0.001f)
        assertEquals(light.centerAlpha, dark.centerAlpha, 0.001f)
        assertEquals(light.edgeAlpha, dark.edgeAlpha, 0.001f)
        assertTrue(light.edgeAlpha < 0.1f)
        assertEquals(1f, light.red, 0.001f)
        assertEquals(1f, light.green, 0.001f)
        assertEquals(1f, light.blue, 0.001f)
        assertTrue(dark.blue > dark.red)
    }

    @Test
    fun widePlateClearsAlongTheLongEdge() {
        val center = ArdttLiquidGlass.ShellCenterAlpha
        val edge = ArdttLiquidGlass.ShellEdgeAlpha
        assertEquals(center, liquidGlassFrostAlpha(center, edge, 0f), 0.001f)
        assertEquals(center, liquidGlassFrostAlpha(center, edge, ArdttLiquidGlass.FrostKnee), 0.001f)
        assertEquals(edge, liquidGlassFrostAlpha(center, edge, 1f), 0.001f)
        val between = liquidGlassFrostAlpha(center, edge, (ArdttLiquidGlass.FrostKnee + 1f) / 2f)
        assertTrue(between < center)
        assertTrue(between > edge)

        val radius = liquidGlassFrostRadius(300f, 48f)
        assertEquals(24f, radius, 0.01f)
        assertEquals(edge, liquidGlassFrostAlpha(center, edge, 24f / radius), 0.001f)

        val circle = liquidGlassFrostRadius(100f, 100f)
        assertEquals(50f, circle, 0.01f)
        assertEquals(edge, liquidGlassFrostAlpha(center, edge, 50f / circle), 0.001f)
    }

    @Test
    fun cornerRadiusFollowsTheShape() {
        val density = Density(1f)
        val size = Size(100f, 40f)
        val rounded = liquidGlassCornerRadius(
            RoundedCornerShape(12.dp),
            size,
            LayoutDirection.Ltr,
            density,
        )
        assertEquals(12f, rounded, 0.1f)
        val circle = liquidGlassCornerRadius(CircleShape, size, LayoutDirection.Ltr, density)
        assertEquals(20f, circle, 0.1f)
    }

    @Test
    fun shaderBendsAcrossThePlateAndSplitsTheRim() {
        val source = liquidGlassAgsl()
        assertTrue(source.contains("uniform shader contents"))
        assertTrue(source.contains("sdRoundBox"))
        assertTrue(source.contains("0.55"))
        assertTrue(source.contains("0.28"))
        assertTrue(source.contains("float dh = 6.0 * u * (1.0 - u)"))
        assertTrue(source.contains("contents.eval(coord - delta)"))
        assertTrue(source.contains("hi.r"))
        assertTrue(source.contains("lo.b"))
        assertTrue(source.contains("shine * 0.55"))
    }

    @Test
    fun refractionRequiresAndroid13() {
        assertTrue(liquidGlassSupported(33))
        assertTrue(liquidGlassSupported(35))
        assertTrue(!liquidGlassSupported(32))
        assertTrue(!liquidGlassSupported(28))
    }
}
