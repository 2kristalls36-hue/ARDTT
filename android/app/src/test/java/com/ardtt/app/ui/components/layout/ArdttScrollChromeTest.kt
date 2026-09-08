package com.ardtt.app.ui.components.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttScrollChromeTest {
    @Test
    fun gpuBlurStartsAtAndroid12() {
        assertFalse(ardttScrollChromeUsesGpuBlur(28))
        assertFalse(ardttScrollChromeUsesGpuBlur(30))
        assertTrue(ardttScrollChromeUsesGpuBlur(31))
        assertTrue(ardttScrollChromeUsesGpuBlur(35))
    }

    @Test
    fun contentStartsBelowFadeAtScrollZero() {
        val chrome = 72.dp
        val fade = 28.dp
        assertEquals(100.dp, ardttScrollChromeTopPadding(chrome, fade))
    }
}
