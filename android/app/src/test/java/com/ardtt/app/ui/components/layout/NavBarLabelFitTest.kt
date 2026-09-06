package com.ardtt.app.ui.components.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBarLabelFitTest {
    @Test
    fun keepsMaxSizeWhenTextAlreadyFits() {
        val size = fitNavLabelSp(maxWidthPx = 80) { 40 }
        assertEquals(10f, size)
    }

    @Test
    fun shrinksUntilMeasuredWidthFits() {
        // 7-letter captions at 10 sp overflow a 40 px tab; 8 sp fits.
        val size = fitNavLabelSp(maxWidthPx = 40) { sp -> (sp * 5f).toInt() }
        assertEquals(8f, size)
    }

    @Test
    fun stopsAtMinimumWhenEvenThatOverflows() {
        val size = fitNavLabelSp(maxWidthPx = 10) { 80 }
        assertEquals(7.5f, size)
    }

    @Test
    fun usesMinimumWhenWidthIsUnknown() {
        assertEquals(7.5f, fitNavLabelSp(maxWidthPx = 0) { 10 })
    }

    @Test
    fun longAdminCaptionsShrinkOnANarrowTab() {
        val labels = listOf("Туннель", "Сервера", "Профили")
        labels.forEach { label ->
            val size = fitNavLabelSp(maxWidthPx = 36) { sp ->
                (label.length * sp * 0.62f).toInt()
            }
            assertTrue("$label stayed $size", size < 10f)
            assertTrue("$label dropped below floor: $size", size >= 7.5f)
        }
    }
}
