package com.ardtt.app.ui.components.surface

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttLiquidGlassTest {
    @Test
    fun bendIsOffInTheMiddleAndFullPastTheRim() {
        assertEquals(0f, liquidGlassEdgeWeight(0f), 0.001f)
        assertEquals(0f, liquidGlassEdgeWeight(ArdttLiquidGlass.EdgeInner), 0.001f)
        val midRadius = ArdttLiquidGlass.EdgeInner + ArdttLiquidGlass.EdgeSpan / 2f
        assertEquals(0.5f, liquidGlassEdgeWeight(midRadius), 0.001f)
        val outer = ArdttLiquidGlass.EdgeInner + ArdttLiquidGlass.EdgeSpan
        assertEquals(1.05f, outer, 0.001f)
        assertEquals(1f, liquidGlassEdgeWeight(outer), 0.001f)
        assertEquals(1f, liquidGlassEdgeWeight(outer + 1f), 0.001f)
    }

    @Test
    fun sampleDoesNotMoveAtTheCenterAndClearsTheRim() {
        val center = liquidGlassSampleOffset(Offset.Zero)
        assertEquals(0f, center.x, 0.001f)
        assertEquals(0f, center.y, 0.001f)

        val inside = liquidGlassSampleOffset(Offset(ArdttLiquidGlass.EdgeInner / 2f, 0f))
        assertEquals(0f, inside.x, 0.001f)

        val rim = liquidGlassSampleOffset(Offset(0.5f, 0f))
        assertTrue(rim.x > 24f)

        val corner = liquidGlassSampleOffset(Offset(0.5f, 0.5f))
        val cornerDistance = hypot(corner.x.toDouble(), corner.y.toDouble()).toFloat()
        assertTrue(cornerDistance < ArdttLiquidGlass.SampleMarginPx)
    }

    @Test
    fun hueLockedFrostKeepsTheButtonAlphaInTheCenter() {
        val red = Color(0xFFBA1A1A).copy(alpha = ArdttFloatingShell.ButtonAlpha)
        val frost = liquidGlassFrost(red, hueLocked = true)
        assertEquals(ArdttFloatingShell.ButtonAlpha, frost.centerAlpha, 0.001f)
        assertEquals(ArdttFloatingShell.ButtonAlpha * ArdttLiquidGlass.HueEdgeScale, frost.edgeAlpha, 0.001f)
        assertEquals(red.red, frost.red, 0.001f)
        assertEquals(red.green, frost.green, 0.001f)
        assertEquals(red.blue, frost.blue, 0.001f)
    }

    @Test
    fun shellFrostIgnoresTheFlatFillAlpha() {
        val light = liquidGlassFrost(Color.White.copy(alpha = 0.96f), hueLocked = false)
        val dark = liquidGlassFrost(Color(0xFF16202C).copy(alpha = 0.88f), hueLocked = false)
        assertEquals(ArdttLiquidGlass.ShellCenterAlpha, light.centerAlpha, 0.001f)
        assertEquals(ArdttLiquidGlass.ShellEdgeAlpha, light.edgeAlpha, 0.001f)
        assertEquals(light.centerAlpha, dark.centerAlpha, 0.001f)
        assertEquals(light.edgeAlpha, dark.edgeAlpha, 0.001f)
        assertEquals(1f, light.red, 0.001f)
        assertEquals(1f, light.green, 0.001f)
        assertEquals(1f, light.blue, 0.001f)
        assertTrue(dark.blue > dark.red)
    }

    @Test
    fun frostHoldsInTheMiddleAndThinsTowardTheCorner() {
        val center = ArdttLiquidGlass.ShellCenterAlpha
        val edge = ArdttLiquidGlass.ShellEdgeAlpha
        assertEquals(center, liquidGlassFrostAlpha(center, edge, 0f), 0.001f)
        assertEquals(center, liquidGlassFrostAlpha(center, edge, ArdttLiquidGlass.FrostKnee), 0.001f)
        assertEquals(edge, liquidGlassFrostAlpha(center, edge, 1f), 0.001f)
        val between = liquidGlassFrostAlpha(center, edge, (ArdttLiquidGlass.FrostKnee + 1f) / 2f)
        assertTrue(between < center)
        assertTrue(between > edge)
        val radius = liquidGlassFrostRadius(100f, 100f)
        assertEquals(hypot(100.0, 100.0).toFloat() * 0.5f, radius, 0.01f)
        val circleRim = 50f / radius
        val rimAlpha = liquidGlassFrostAlpha(center, edge, circleRim)
        assertTrue(rimAlpha < center)
        assertTrue(rimAlpha > edge)
    }

    @Test
    fun shaderUsesTheSameRimCurveAsTheOffset() {
        val source = liquidGlassAgsl()
        assertTrue(source.contains("uniform shader contents"))
        assertTrue(source.contains("0.45"))
        assertTrue(source.contains("0.60"))
        assertTrue(source.contains("t * t * (3.0 - 2.0 * t)"))
        assertTrue(source.contains("contents.eval"))
    }

    @Test
    fun refractionRequiresAndroid13() {
        assertTrue(liquidGlassSupported(33))
        assertTrue(liquidGlassSupported(35))
        assertTrue(!liquidGlassSupported(32))
        assertTrue(!liquidGlassSupported(28))
    }
}
