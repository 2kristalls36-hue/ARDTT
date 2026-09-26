package com.ardtt.app.ui.components.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.ardtt.app.ui.AppDestination
import com.ardtt.app.ui.theme.ArdttMotion
import kotlin.math.abs
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
    fun tabIconMotionsRestAtBothEndsOfTheSecond() {
        assertEquals(1_000, ArdttMotion.TabIcon)
        for (motion in TabIconMotion.entries) {
            assertTrue(motion.name, tabIconPoseAtRest(tabIconPose(motion, 0f)))
            assertTrue(motion.name, tabIconPoseAtRest(tabIconPose(motion, 1f)))
        }
    }

    @Test
    fun settingsGearTurnsHalfWayAndReturns() {
        assertEquals(TabIconMotion.GearHalfTurn, tabIconMotionFor(AppDestination.Settings.route))
        val mid = tabIconPose(TabIconMotion.GearHalfTurn, 0.5f)
        assertEquals(180f, mid.rotationZ, 0.01f)
        assertEquals(0f, mid.translationXFraction, 0.01f)
        assertEquals(0f, mid.translationYFraction, 0.01f)
        assertEquals(1f, mid.scale, 0.01f)
        val quarter = tabIconPose(TabIconMotion.GearHalfTurn, 0.25f).rotationZ
        assertTrue(quarter in 1f..179f)
        var previous = 0f
        for (step in 1..10) {
            val rotation = tabIconPose(TabIconMotion.GearHalfTurn, step / 20f).rotationZ
            assertTrue(rotation >= previous)
            assertTrue(rotation - previous < 40f)
            previous = rotation
        }
    }

    @Test
    fun tunnelKeyRollsUpsideDownAroundTheHorizontalAxis() {
        val start = tabIconPose(TabIconMotion.KeyTurn, 0f)
        val upsideDown = tabIconPose(TabIconMotion.KeyTurn, 0.5f)
        val end = tabIconPose(TabIconMotion.KeyTurn, 1f)
        assertEquals(0f, start.rotationX, 0.01f)
        assertEquals(0f, start.rotationZ, 0.01f)
        assertEquals(180f, upsideDown.rotationX, 0.01f)
        assertEquals(0f, upsideDown.rotationZ, 0.01f)
        assertEquals(KeyTurnDegrees, end.rotationX, 0.01f)
        assertEquals(360f, end.rotationX, 0.01f)
        assertEquals(1f, tabIconKeyScaleY(start.rotationX), 0.001f)
        assertEquals(-1f, tabIconKeyScaleY(upsideDown.rotationX), 0.001f)
        assertEquals(1f, tabIconKeyScaleY(end.rotationX), 0.001f)
        assertTrue(tabIconPoseAtRest(end))
        var previousScale = tabIconKeyScaleY(start.rotationX)
        for (step in 1..60) {
            val scale = tabIconKeyScaleY(tabIconPose(TabIconMotion.KeyTurn, step / 60f).rotationX)
            assertTrue(abs(scale - previousScale) < 0.2f)
            previousScale = scale
        }
        val gear = tabIconPose(TabIconMotion.GearHalfTurn, 0.5f)
        assertEquals(0f, gear.rotationX, 0.01f)
        assertEquals(180f, gear.rotationZ, 0.01f)
        val opening = tabIconPose(TabIconMotion.KeyTurn, 0.1f).rotationX
        val fastest = tabIconPose(TabIconMotion.KeyTurn, 0.5f).rotationX -
            tabIconPose(TabIconMotion.KeyTurn, 0.4f).rotationX
        assertTrue(opening < fastest)
        var previous = 0f
        for (step in 1..20) {
            val rotation = tabIconPose(TabIconMotion.KeyTurn, step / 20f).rotationX
            assertTrue(rotation >= previous)
            assertTrue(rotation - previous < 40f)
            previous = rotation
        }
    }

    @Test
    fun eachPrimaryTabHasItsOwnReturningMotion() {
        assertEquals(TabIconMotion.KeyTurn, tabIconMotionFor(AppDestination.Tunnel.route))
        assertEquals(TabIconMotion.ServerLights, tabIconMotionFor(AppDestination.Servers.route))
        assertEquals(1f, tabIconServerLightAlpha(0f), 0.001f)
        assertEquals(1f, tabIconServerLightAlpha(0.5f), 0.001f)
        assertEquals(1f, tabIconServerLightAlpha(1f), 0.001f)
        assertEquals(0f, tabIconServerLightAlpha(0.25f), 0.001f)
        assertEquals(0f, tabIconServerLightAlpha(0.75f), 0.001f)
        assertEquals(24f, ArdttServersChassis.viewportWidth, 0.01f)
        assertEquals(24f, ArdttServersLights.viewportWidth, 0.01f)
        assertEquals(ArdttServersChassis.viewportHeight, ArdttServersLights.viewportHeight, 0.01f)
        assertEquals(TabIconMotion.Lift, tabIconMotionFor(AppDestination.Profiles.route))
        assertEquals(TabIconMotion.Slide, tabIconMotionFor(AppDestination.Exceptions.route))
        assertEquals(TabIconMotion.Pulse, tabIconMotionFor(AppDestination.Network.route))
        assertEquals(TabIconMotion.Heartbeat, tabIconMotionFor(AppDestination.Diagnostics.route))
        val heart = tabIconPose(TabIconMotion.Heartbeat, 0.25f)
        assertTrue(heart.scale > 1f)
        assertTrue(tabIconPoseAtRest(tabIconPose(TabIconMotion.Heartbeat, 0.5f)))
    }

    @Test
    fun clickMarksPendingOnlyWhenRouteChanges() {
        assertEquals("logs", navTabPendingRoute(currentRoute = "tunnel", clickedRoute = "logs"))
        assertNull(navTabPendingRoute(currentRoute = "logs", clickedRoute = "logs"))
    }
}
