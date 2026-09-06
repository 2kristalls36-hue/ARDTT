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
        assertTrue(cmd.contains("ARDTT_AUTO_PORTS=1"))
        assertFalse(cmd.contains("ARDTT_CASCADE_PEER_PUBLIC_KEY"))
        assertFalse(cmd.contains("password"))
        assertFalse(cmd.contains("ARDTT_GIT_REF"))
        assertTrue(cmd.endsWith("bash /opt/ardtt/install.sh"))
    }

    @Test
    fun manualPortsDisableAutoFlag() {
        val cmd = DeployInstallEnv.command(
            publicHost = "45.129.2.3",
            directPort = 51821,
            bypassPort = 56004,
            deployVersion = "1.0.35",
            role = "entry",
            autoPorts = false,
        )
        assertTrue(cmd.contains("ARDTT_AUTO_PORTS=0"))
        assertTrue(cmd.contains("ARDTT_DIRECT_PORT=51821"))
        assertTrue(cmd.contains("ARDTT_BYPASS_PORT=56004"))
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
    fun gitRefPassedForRepoFetch() {
        val cmd = DeployInstallEnv.command(
            publicHost = "45.129.2.3",
            directPort = 51820,
            bypassPort = 56003,
            deployVersion = "1.0.36",
            role = "entry",
            gitRepo = "https://github.com/2kristalls36-hue/ARDTT.git",
            gitRef = "v0.5.238",
        )
        assertTrue(cmd.contains("ARDTT_GIT_REPO='https://github.com/2kristalls36-hue/ARDTT.git'"))
        assertTrue(cmd.contains("ARDTT_GIT_REF='v0.5.238'"))
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
        assertEquals(
            "2.26.125.160:51821",
            DeployInstallEnv.peerEndpoint("2.26.125.160", 51821),
        )
    }

    @Test
    fun doneLineParsesResolvedPorts() {
        val fields = DeployInstallEnv.doneFields(
            "ARDTT_DONE|install_dir=/opt/ardtt|public_host=1.2.3.4|" +
                "direct_port=51821|bypass_port=56004|cascade_listen_port=51822|auto_ports=1",
        )
        assertEquals("/opt/ardtt", fields["install_dir"])
        assertEquals(51821, DeployInstallEnv.intField(fields, "direct_port"))
        assertEquals(56004, DeployInstallEnv.intField(fields, "bypass_port"))
        assertEquals(51822, DeployInstallEnv.intField(fields, "cascade_listen_port"))
        assertNull(DeployInstallEnv.intField(fields, "missing"))
        assertTrue(DeployInstallEnv.doneFields("ARDTT_WARN|x").isEmpty())
    }
}
