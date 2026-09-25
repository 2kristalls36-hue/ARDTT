package com.ardtt.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttNavSwapTest {
    @Test
    fun outgoingScreenIsHiddenWhileNavHostStillComposesBoth() {
        assertTrue(
            "NavHost 2.8 spring keeps both destinations composed; None leaves the " +
                "outgoing screen opaque and stacked over the incoming one.",
            ArdttNavSwap.hideOutgoingWhileIncomingComposed,
        )
        assertEquals(0, ArdttNavSwap.durationMs)
    }
}
