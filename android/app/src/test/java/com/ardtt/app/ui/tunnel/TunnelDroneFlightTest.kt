package com.ardtt.app.ui.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelDroneFlightTest {
    private val spec = FlightAssetSpec(
        resId = 0,
        sizeDp = 80,
        startXFrac = 0f,
        startYFrac = 0f,
        anchorXFrac = 0.5f,
        anchorYFrac = 0.5f,
        orbitRadiusXFrac = 0.02f,
        orbitRadiusYFrac = 0.02f,
        orbitDurationMs = 1_000,
        delayMs = 0L,
        phaseRad = 0f,
        windStrength = 1f,
        gustFreqMul = 1f,
        gustPhase = 0f,
        compensationStrength = 0.2f,
        dragLimitXFrac = 0.08f,
        dragLimitYFrac = 0.05f,
    )

    @Test
    fun arrivalMovesFromStartToAnchorWithoutOrbit() {
        val start = flightPose(
            spec = spec,
            elapsedSec = 0f,
            sceneWidthPx = 1000f,
            sceneHeightPx = 800f,
            arrivalProgress = 0f,
            orbitBlend = 0f,
            blowAwayProgress = 0f,
            dragDx = 0f,
            dragDy = 0f,
        )
        val landed = flightPose(
            spec = spec,
            elapsedSec = 0f,
            sceneWidthPx = 1000f,
            sceneHeightPx = 800f,
            arrivalProgress = 1f,
            orbitBlend = 0f,
            blowAwayProgress = 0f,
            dragDx = 0f,
            dragDy = 0f,
        )
        assertEquals(0f, start.x, 0.01f)
        assertEquals(0f, start.y, 0.01f)
        assertEquals(500f, landed.x, 0.01f)
        assertEquals(400f, landed.y, 0.01f)
        assertEquals(start.alpha, 0.22f, 0.001f)
        assertEquals(landed.alpha, 1f, 0.001f)
    }

    @Test
    fun orbitIsPeriodicAndBlowAwayFadesOut() {
        val a = flightPose(spec, 0.25f, 1000f, 800f, 1f, 1f, 0f, 0f, 0f)
        val b = flightPose(spec, 1.25f, 1000f, 800f, 1f, 1f, 0f, 0f, 0f)
        assertEquals(a.x, b.x, 0.05f)
        assertEquals(a.y, b.y, 0.05f)
        val gone = flightPose(spec, 0.25f, 1000f, 800f, 1f, 1f, 1f, 0f, 0f)
        assertTrue(gone.alpha < 0.05f)
        assertTrue(gone.x < a.x)
    }
}
