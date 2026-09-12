package com.ardtt.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckThrottleTest {
    @Test
    fun firstBackgroundCheckAlwaysRuns() {
        assertTrue(shouldRunBackgroundCheck(lastCheckAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun repeatVisitWithinIntervalReusesLastCheck() {
        val last = 10_000_000L
        assertFalse(shouldRunBackgroundCheck(lastCheckAtMs = last, nowMs = last + 1L))
        assertFalse(
            shouldRunBackgroundCheck(
                lastCheckAtMs = last,
                nowMs = last + BACKGROUND_CHECK_INTERVAL_MS - 1L,
            ),
        )
    }

    @Test
    fun checkRunsAgainOnceIntervalElapsed() {
        val last = 10_000_000L
        assertTrue(
            shouldRunBackgroundCheck(
                lastCheckAtMs = last,
                nowMs = last + BACKGROUND_CHECK_INTERVAL_MS,
            ),
        )
    }
}
