package com.ardtt.app.ui.components.surface

import androidx.compose.ui.graphics.Color
import com.ardtt.app.ui.theme.ArdttSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttFloatingShellTest {
    @Test
    fun tintedShellKeepsTheHueAtEightyPercent() {
        val red = Color(0xFFBA1A1A)
        val glass = ArdttFloatingShell.tintedShell(red)
        assertEquals(ArdttFloatingShell.ButtonAlpha, 0.80f, 0.001f)
        assertEquals(0.80f, glass.alpha, 0.001f)
        assertEquals(red.red, glass.red, 0.001f)
        assertEquals(red.green, glass.green, 0.001f)
        assertEquals(red.blue, glass.blue, 0.001f)
    }

    @Test
    fun opaqueFillMatchesGlassSeenOnTheSurface() {
        val surface = Color(0xFFFAFCFF)
        val primary = Color(0xFF1565C0)
        val solid = ArdttFloatingShell.opaqueGlassFill(primary, surface)
        assertEquals(
            ArdttSurface.compositeOver(ArdttFloatingShell.tintedShell(primary), surface),
            solid,
        )
        assertEquals(1f, solid.alpha, 0.001f)
        assertTrue(solid.red > primary.red)
        assertTrue(solid.blue > solid.red)
        val label = ArdttSurface.contentColorOn(solid)
        assertEquals(ArdttSurface.DarkContent, label)
        assertTrue(ArdttSurface.contrastRatio(label, solid) > 4.3f)

        val darkPrimary = Color(0xFFA8D0FF)
        val darkSurface = Color(0xFF16202C)
        val darkSolid = ArdttFloatingShell.opaqueGlassFill(darkPrimary, darkSurface)
        assertTrue(darkSolid.red < darkPrimary.red)
        assertTrue(
            ArdttSurface.contrastRatio(ArdttSurface.contentColorOn(darkSolid), darkSolid) >=
                ArdttSurface.TextContrastMin,
        )

        val error = Color(0xFFBA1A1A)
        val solidError = ArdttFloatingShell.opaqueGlassFill(error, surface)
        assertTrue(solidError.red > solidError.green)
        assertTrue(solidError.red > solidError.blue)
    }

    @Test
    fun tintedShellIgnoresIncomingAlpha() {
        val red = Color(0xFFBA1A1A)
        assertEquals(
            ArdttFloatingShell.tintedShell(red),
            ArdttFloatingShell.tintedShell(red.copy(alpha = 0.2f)),
        )
    }
}
