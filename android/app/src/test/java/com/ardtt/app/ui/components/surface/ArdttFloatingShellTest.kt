package com.ardtt.app.ui.components.surface

import androidx.compose.ui.graphics.Color
import com.ardtt.app.ui.theme.ArdttSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttFloatingShellTest {
    @Test
    fun tintedShellMovesTowardOpaqueTintWithoutBecomingSolid() {
        val shell = Color(0xFF101418).copy(alpha = ArdttSurface.ShellAlphaDark)
        val tint = Color(0xFF1565C0)
        val mixed = ArdttFloatingShell.tintedShell(shell, tint, mix = 0.34f)
        assertTrue(mixed.alpha > shell.alpha)
        assertTrue(mixed.alpha < 1f)
        assertTrue(mixed.blue > shell.blue)
        val solid = ArdttFloatingShell.tintedShell(shell, tint, mix = 1f)
        assertEquals(tint.red, solid.red, 0.001f)
        assertEquals(tint.blue, solid.blue, 0.001f)
        assertEquals(1f, solid.alpha, 0.001f)
    }

    @Test
    fun tintedShellClampsMixAndIgnoresTintAlpha() {
        val shell = Color(0xFFE8EEF4).copy(alpha = ArdttSurface.ShellAlphaLight)
        val tint = Color(0xFFB35C00).copy(alpha = 0.2f)
        val untouched = ArdttFloatingShell.tintedShell(shell, tint, mix = -1f)
        assertEquals(shell, untouched)
        val punched = ArdttFloatingShell.tintedShell(shell, tint, mix = 0.34f)
        val opaqueTint = ArdttFloatingShell.tintedShell(shell, tint.copy(alpha = 1f), mix = 0.34f)
        assertEquals(opaqueTint, punched)
    }
}
