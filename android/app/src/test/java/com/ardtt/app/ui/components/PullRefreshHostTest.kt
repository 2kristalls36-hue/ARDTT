package com.ardtt.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PullRefreshHostTest {
    @Test
    fun holdKeepsSpinnerVisibleForFastRefresh() {
        assertEquals(450L, pullRefreshHoldMs(0L))
        assertEquals(50L, pullRefreshHoldMs(400L))
        assertEquals(0L, pullRefreshHoldMs(450L))
        assertEquals(0L, pullRefreshHoldMs(2_000L))
    }

    @Test
    fun indicatorAndTravelAreFixedAcrossTabs() {
        assertEquals(52, PULL_REFRESH_INDICATOR_TOP.value.toInt())
        assertEquals(80, PULL_REFRESH_FEED_TRAVEL.value.toInt())
    }
}
