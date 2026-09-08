package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshJumpTest {
    @Test
    fun targetHostStripsPortSuffix() {
        assertEquals("2.26.125.160", SshJump.targetHost("2.26.125.160"))
        assertEquals("2.26.125.160", SshJump.targetHost("2.26.125.160:22"))
        assertEquals("exit.example", SshJump.targetHost("  exit.example:2222 "))
    }

    @Test
    fun connectErrorNamesBothHopsAndCause() {
        val text = SshJump.connectError(
            entryHost = "10.0.0.1",
            exitHost = "10.8.0.2",
            cause = IllegalStateException("channel is not opened."),
        )
        assertTrue(text.contains("10.8.0.2"))
        assertTrue(text.contains("10.0.0.1"))
        assertTrue(text.contains("AllowTcpForwarding"))
        assertTrue(text.contains("channel is not opened"))
    }
}
