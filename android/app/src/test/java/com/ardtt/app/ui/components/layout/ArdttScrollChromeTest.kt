package com.ardtt.app.ui.components.layout

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
}
