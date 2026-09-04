package com.ardtt.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSectionCardDefaultsTest {
    @Test
    fun contourMatchesAppearanceHairline() {
        val primary = Color(0xFF3D7AB5)
        val color = AppSectionCardDefaults.contourColor(primary)
        assertEquals(AppSectionCardDefaults.ContourAlpha, color.alpha, 1e-5f)
        assertEquals(primary.red, color.red, 1e-5f)
        assertEquals(primary.green, color.green, 1e-5f)
        assertEquals(primary.blue, color.blue, 1e-5f)

        val border = AppSectionCardDefaults.contourBorder(primary)
        assertEquals(2f, border.width.value, 0.01f)
        val brush = border.brush
        assertTrue(brush is SolidColor)
        assertEquals(color, (brush as SolidColor).value)
    }
}
