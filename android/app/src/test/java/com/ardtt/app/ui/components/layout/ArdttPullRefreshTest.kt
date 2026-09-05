package com.ardtt.app.ui.components.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class ArdttPullRefreshTest {
    @Test
    fun holdKeepsSpinnerVisibleForFastRefresh() {
        assertEquals(450L, pullRefreshHoldMs(0L))
        assertEquals(50L, pullRefreshHoldMs(400L))
        assertEquals(0L, pullRefreshHoldMs(450L))
        assertEquals(0L, pullRefreshHoldMs(2_000L))
    }

    @Test
    fun indicatorAndTravelAreFixedAcrossTabs() {
        assertEquals(52, ArdttPullRefreshDefaults.IndicatorTop.value.toInt())
        assertEquals(80, ArdttPullRefreshDefaults.FeedTravel.value.toInt())
    }
}
