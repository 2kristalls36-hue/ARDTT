package com.ardtt.app.ui.components.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

class ArdttLinearProgressTest {
    @Test
    fun coerceLinearProgressClampsOutsideUnitInterval() {
        assertEquals(0f, coerceLinearProgress(-0.2f))
        assertEquals(1f, coerceLinearProgress(1.4f))
        assertEquals(0.37f, coerceLinearProgress(0.37f))
    }
}
