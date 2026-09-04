package com.ardtt.app.ui.admin

import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.profile.BypassConfig
import com.ardtt.app.profile.DirectConfig
import com.ardtt.app.profile.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveDeployMatchTest {
    private fun server(
        id: String,
        host: String,
        publicHost: String = "",
        lastDeployedAtMs: Long = 0L,
    ) = DeployTarget(
        id = id,
        name = id,
        host = host,
        publicHost = publicHost,
        lastDeployedAtMs = lastDeployedAtMs,
    )

    private fun profile(endpointHost: String) = VpnProfile(
        name = "p",
        deviceId = "d",
        hostId = 1,
        direct = DirectConfig(
            endpoint = "$endpointHost:51820",
            privateKey = "",
            peerPublicKey = "",
            address = "10.8.0.2/32",
            dns = listOf("10.8.0.1"),
            mtu = 1280,
            awg = emptyMap(),
        ),
        bypass = BypassConfig(
            peer = "$endpointHost:56003",
            address = "10.9.0.2/32",
            password = "",
            workers = 3,
            transport = "tcp",
            mode = "raw",
            dial = "auto",
        ),
    )

    @Test
    fun prefersPublicHostOverHost() {
        val servers = listOf(
            server("ssh", host = "203.0.113.10", publicHost = ""),
            server("pub", host = "10.0.0.1", publicHost = "203.0.113.10"),
        )
        val id = findActiveDeployServerId(servers, activeProfileHost(profile("203.0.113.10")))
        assertEquals("pub", id)
    }

    @Test
    fun matchesHostWhenPublicHostEmpty() {
        val servers = listOf(
            server("a", host = "10.0.0.1"),
            server("b", host = "203.0.113.10"),
        )
        val id = findActiveDeployServerId(servers, activeProfileHost(profile("203.0.113.10")))
        assertEquals("b", id)
    }

    @Test
    fun fallsBackToMostRecentDeployWhenNoProfileMatch() {
        val servers = listOf(
            server("old", host = "10.0.0.1", lastDeployedAtMs = 100L),
            server("new", host = "10.0.0.2", lastDeployedAtMs = 200L),
        )
        val id = findActiveDeployServerId(servers, activeProfileHost(profile("9.9.9.9")))
        assertEquals("new", id)
    }

    @Test
    fun noServersReturnsNull() {
        assertNull(findActiveDeployServerId(emptyList(), "1.2.3.4"))
    }

    @Test
    fun deployFormStaysOpenWhileBusy() {
        assertFalse(deployFormCanLeave(busy = true))
        assertTrue(deployFormCanLeave(busy = false))
    }
}
