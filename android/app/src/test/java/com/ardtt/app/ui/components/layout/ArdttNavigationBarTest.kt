package com.ardtt.app.ui.components.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttNavigationBarTest {
    private val selectedColor = Color(0xFF1565C0)
    private val unselectedColor = Color(0xFF8A8A8A)

    @Test
    fun pressedTabUsesActiveColorBeforeIndicatorArrives() {
        val paint = navTabPaint(
            selected = false,
            pending = false,
            pressed = true,
            emphasis = 0f,
            selectedColor = selectedColor,
            unselectedColor = unselectedColor,
        )
        assertEquals(selectedColor, paint.color)
        assertTrue(paint.bold)
        assertEquals(1f, paint.labelAlpha, 0f)
    }

    @Test
    fun pendingTabUsesActiveColorBeforeIndicatorArrives() {
        val paint = navTabPaint(
            selected = false,
            pending = true,
            pressed = false,
            emphasis = 0f,
            selectedColor = selectedColor,
            unselectedColor = unselectedColor,
        )
        assertEquals(selectedColor, paint.color)
        assertTrue(paint.bold)
        assertEquals(1f, paint.labelAlpha, 0f)
    }

    @Test
    fun selectedTabStaysActiveColorWhileIndicatorIsStillMoving() {
        val paint = navTabPaint(
            selected = true,
            pending = false,
            pressed = false,
            emphasis = 0f,
            selectedColor = selectedColor,
            unselectedColor = unselectedColor,
        )
        assertEquals(selectedColor, paint.color)
    }

    @Test
    fun idleTabFollowsIndicatorEmphasis() {
        val paint = navTabPaint(
            selected = false,
            pending = false,
            pressed = false,
            emphasis = 0.5f,
            selectedColor = selectedColor,
            unselectedColor = unselectedColor,
        )
        assertEquals(lerp(unselectedColor, selectedColor, 0.5f), paint.color)
        assertFalse(paint.bold)
        assertEquals(1f, paint.labelAlpha, 0f)
    }

    @Test
    fun distantIdleTabStaysMuted() {
        val paint = navTabPaint(
            selected = false,
            pending = false,
            pressed = false,
            emphasis = 0f,
            selectedColor = selectedColor,
            unselectedColor = unselectedColor,
        )
        assertEquals(unselectedColor, paint.color)
        assertFalse(paint.bold)
        assertEquals(0.92f, paint.labelAlpha, 0f)
    }

    @Test
    fun clickMarksPendingOnlyWhenRouteChanges() {
        assertEquals("logs", navTabPendingRoute(currentRoute = "tunnel", clickedRoute = "logs"))
        assertNull(navTabPendingRoute(currentRoute = "logs", clickedRoute = "logs"))
    }
}
