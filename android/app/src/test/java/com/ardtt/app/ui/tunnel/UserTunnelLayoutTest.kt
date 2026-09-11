package com.ardtt.app.ui.tunnel

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTunnelLayoutTest {
    @Test
    fun ringKeepsFullSizeOnTallPhones() {
        assertEquals(UserTunnelDefaults.RingSize, UserTunnelDefaults.ringSize(viewportHeight = 800.dp))
        assertEquals(UserTunnelDefaults.RingSize, UserTunnelDefaults.ringSize(viewportHeight = 640.dp))
    }

    @Test
    fun ringShrinksWithShortViewportsButNotBelowMinimum() {
        val short = UserTunnelDefaults.ringSize(viewportHeight = 480.dp)
        assertTrue(short < UserTunnelDefaults.RingSize)
        assertTrue(short >= UserTunnelDefaults.MinRingSize)
        assertEquals(UserTunnelDefaults.MinRingSize, UserTunnelDefaults.ringSize(viewportHeight = 300.dp))
    }

    @Test
    fun ringNeverDominatesAShortViewport() {
        for (height in listOf(320, 360, 480, 560)) {
            val ring = UserTunnelDefaults.ringSize(height.dp)
            assertTrue("ring $ring for $height dp", ring <= height.dp * 0.45f)
        }
    }

    @Test
    fun sideBySideOnlyForShortLandscape() {
        assertTrue(UserTunnelDefaults.sideBySide(viewportWidth = 800.dp, viewportHeight = 360.dp))
        assertFalse(UserTunnelDefaults.sideBySide(viewportWidth = 360.dp, viewportHeight = 800.dp))
        // Tablet landscape is tall enough for the stacked layout.
        assertFalse(UserTunnelDefaults.sideBySide(viewportWidth = 1280.dp, viewportHeight = 800.dp))
        // Square-ish foldable: stacked.
        assertFalse(UserTunnelDefaults.sideBySide(viewportWidth = 600.dp, viewportHeight = 600.dp))
    }

    @Test
    fun powerButtonFitsInsideTheRing() {
        assertTrue(UserTunnelDefaults.PowerButtonSize < UserTunnelDefaults.RingSize)
        assertTrue(UserTunnelDefaults.PowerIconSize < UserTunnelDefaults.PowerButtonSize)
        assertTrue(UserTunnelDefaults.PauseBarHeight < UserTunnelDefaults.PowerButtonSize)
    }
}
