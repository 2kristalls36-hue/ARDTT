package com.ardtt.app.ui.components.surface

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Color channels are stored with 8-bit precision, so compare within one step. */
private const val CHANNEL_TOLERANCE = 1f / 255f

class ArdttSectionCardDefaultsTest {
    @Test
    fun contourMatchesAppearanceHairline() {
        val primary = Color(0xFF3D7AB5)
        val color = ArdttSectionCardDefaults.contourColor(primary)
        assertEquals(ArdttSectionCardDefaults.ContourAlpha, color.alpha, CHANNEL_TOLERANCE)
        assertEquals(primary.red, color.red, CHANNEL_TOLERANCE)
        assertEquals(primary.green, color.green, CHANNEL_TOLERANCE)
        assertEquals(primary.blue, color.blue, CHANNEL_TOLERANCE)

        val border = ArdttSectionCardDefaults.contourBorder(primary)
        assertEquals(2f, border.width.value, 0.01f)
        val brush = border.brush
        assertTrue(brush is SolidColor)
        assertEquals(color, (brush as SolidColor).value)
    }
}
