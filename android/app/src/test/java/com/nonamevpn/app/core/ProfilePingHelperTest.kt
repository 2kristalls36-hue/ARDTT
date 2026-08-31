package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePingHelperTest {
    @Test
    fun tcpConnectToLocalClosedPortFailsFast() {
        val ms = ProfilePingHelper.tcpConnectMs("127.0.0.1", 1, timeoutMs = 400)
        assertEquals(-1L, ms)
    }

    @Test
    fun tcpConnectToReachableHttpIsPositive() {
        // Best-effort: skip soft if offline / filtered.
        val ms = ProfilePingHelper.tcpConnectMs("1.1.1.1", 443, timeoutMs = 2_000)
        if (ms >= 0L) {
            assertTrue(ms in 1L..2_000L)
        }
    }
}
