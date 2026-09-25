package com.ardtt.app.ui.components.surface

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
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
    fun tintedShellIgnoresIncomingAlpha() {
        val red = Color(0xFFBA1A1A)
        assertEquals(
            ArdttFloatingShell.tintedShell(red),
            ArdttFloatingShell.tintedShell(red.copy(alpha = 0.2f)),
        )
    }
}
