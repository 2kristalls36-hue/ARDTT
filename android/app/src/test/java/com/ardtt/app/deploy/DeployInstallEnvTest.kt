package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployInstallEnvTest {
    @Test
    fun standaloneEntryHasNoCascadePeer() {
        val cmd = DeployInstallEnv.command(
            publicHost = "45.129.2.3",
            directPort = 51820,
            bypassPort = 56003,
            deployVersion = "1.0.20",
            role = "entry",
        )
        assertTrue(cmd.contains("ARDTT_ROLE='entry'"))
        assertTrue(cmd.contains("ARDTT_CASCADE_ENABLED=0"))
        assertFalse(cmd.contains("ARDTT_CASCADE_PEER_PUBLIC_KEY"))
        assertFalse(cmd.contains("password"))
        assertTrue(cmd.endsWith("bash /opt/ardtt/install.sh"))
    }

    @Test
    fun exitRoleDoesNotEmbedSshPassword() {
        val cmd = DeployInstallEnv.command(
            publicHost = "2.26.125.160",
            directPort = 51820,
            bypassPort = 56003,
            deployVersion = "1.0.20",
            role = "exit",
            cascadeEnabled = true,
        )
        assertTrue(cmd.contains("ARDTT_ROLE='exit'"))
        assertTrue(cmd.contains("ARDTT_CASCADE_ENABLED=1"))
        assertFalse(cmd.contains("ARDTT_CASCADE_PASSWORD"))
        assertFalse(cmd.contains("BulTL"))
    }

    @Test
    fun entryCascadePassesPeerKeyAndEndpoint() {
        val cmd = DeployInstallEnv.command(
            publicHost = "45.129.2.3",
            directPort = 51820,
            bypassPort = 56003,
            deployVersion = "1.0.20",
            role = "entry",
            cascadeEnabled = true,
            cascadePeerEndpoint = "2.26.125.160:51820",
            cascadePeerPublicKey = "abc+DEF/123=",
        )
        assertTrue(cmd.contains("ARDTT_CASCADE_PEER_ENDPOINT='2.26.125.160:51820'"))
        assertTrue(cmd.contains("ARDTT_CASCADE_PEER_PUBLIC_KEY='abc+DEF/123='"))
        assertTrue(cmd.contains("ARDTT_CASCADE_DNS='10.10.0.2'"))
    }

    @Test
    fun publicKeyLineParser() {
        assertEquals(
            "abcd",
            DeployInstallEnv.publicKeyFromLine("ARDTT_CASCADE_PUBLIC_KEY|abcd"),
        )
        assertNull(DeployInstallEnv.publicKeyFromLine("ARDTT_DONE|ok"))
        assertEquals(
            "2.26.125.160:51820",
            DeployInstallEnv.peerEndpoint(" 2.26.125.160 "),
        )
    }
}
