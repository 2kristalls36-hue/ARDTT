package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployInstallEnvTest {
    private fun fetchCmd(
        role: String = "entry",
        cascadeEnabled: Boolean = false,
        autoPorts: Boolean = true,
        publicHost: String = "45.129.2.3",
        directPort: Int = 51820,
        bypassPort: Int = 56003,
        deployVersion: String = "1.0.45",
        cascadePeerEndpoint: String = "",
        cascadePeerPublicKey: String = "",
        cascadePeerProvisionPort: Int = 9100,
        provisionPort: Int = 9100,
        telemetryPort: Int = 9200,
    ): String = DeployInstallEnv.fetchAndInstallCommand(
        publicHost = publicHost,
        directPort = directPort,
        bypassPort = bypassPort,
        deployVersion = deployVersion,
        role = role,
        cascadeEnabled = cascadeEnabled,
        cascadePeerEndpoint = cascadePeerEndpoint,
        cascadePeerPublicKey = cascadePeerPublicKey,
        cascadePeerProvisionPort = cascadePeerProvisionPort,
        autoPorts = autoPorts,
        provisionPort = provisionPort,
        telemetryPort = telemetryPort,
    )

    @Test
    fun fetchCommandClampsCpusForSingleCoreHost() {
        val command = DeployInstallEnv.fetchAndInstallCommand(
            publicHost = "45.129.2.3",
            directPort = 51820,
            bypassPort = 56003,
            deployVersion = "1.0.46",
            role = "entry",
            hostCpus = 1,
        )
        assertTrue(command.contains("ARDTT_CPUS=1.0"))
        assertTrue(command.contains("ARDTT_MEM_LIMIT=512m"))
    }

    @Test
    fun fetchCommandUsesTwoCpusWhenHostHasThem() {
        val command = DeployInstallEnv.fetchAndInstallCommand(
            publicHost = "45.129.2.3",
            directPort = 51820,
            bypassPort = 56003,
            deployVersion = "1.0.46",
            role = "entry",
            hostCpus = 2,
        )
        assertTrue(command.contains("ARDTT_CPUS=2.0"))
        assertFalse(command.contains("ARDTT_MEM_LIMIT=512m"))
    }

    @Test
    fun fetchCommandIncludesDiskCleanupWhenRequested() {
        val command = DeployInstallEnv.fetchAndInstallCommand(
            publicHost = "45.129.2.3",
            directPort = 51820,
            bypassPort = 56003,
            deployVersion = "1.0.49",
            role = "entry",
            diskCleanup = true,
        )
        assertTrue(command.contains("ARDTT_DISK_CLEANUP=1"))
        assertTrue(command.contains("fetch-and-install.sh"))
    }

    @Test
    fun fetchCommandOmitsDiskCleanupByDefault() {
        val command = fetchCmd()
        assertFalse(command.contains("ARDTT_DISK_CLEANUP"))
    }

    @Test
    fun manualPortsDisableAutoFlag() {
        val command = fetchCmd(autoPorts = false, directPort = 51821, bypassPort = 56004)
        assertTrue(command.contains("ARDTT_AUTO_PORTS=0"))
        assertTrue(command.contains("ARDTT_DIRECT_PORT=51821"))
        assertTrue(command.contains("ARDTT_BYPASS_PORT=56004"))
        assertTrue(command.contains("ARDTT_PROVISION_PORT=9100"))
        assertTrue(command.contains("ARDTT_TELEMETRY_PORT=9200"))
    }

    @Test
    fun exitRoleDoesNotEmbedSshPassword() {
        val command = fetchCmd(role = "exit", cascadeEnabled = true)
        assertTrue(command.contains("ARDTT_ROLE='exit'"))
        assertTrue(command.contains("ARDTT_CASCADE_ENABLED=1"))
        assertFalse(command.contains("ARDTT_CASCADE_PASSWORD"))
        assertFalse(command.contains("BulTL"))
    }

    @Test
    fun entryCascadePassesPeerKeyEndpointAndProvisionPort() {
        val command = fetchCmd(
            cascadeEnabled = true,
            cascadePeerEndpoint = "2.26.125.160:51820",
            cascadePeerPublicKey = "abc+DEF/123=",
            cascadePeerProvisionPort = 9101,
        )
        assertTrue(command.contains("ARDTT_CASCADE_PEER_ENDPOINT='2.26.125.160:51820'"))
        assertTrue(command.contains("ARDTT_CASCADE_PEER_PUBLIC_KEY='abc+DEF/123='"))
        assertTrue(command.contains("ARDTT_CASCADE_PEER_PROVISION_PORT=9101"))
        assertTrue(command.contains("ARDTT_CASCADE_DNS='10.10.0.2'"))
    }

    @Test
    fun noGitFallbackInCommand() {
        val command = fetchCmd()
        assertFalse(command.contains("ARDTT_GIT_REPO"))
        assertFalse(command.contains("ARDTT_GIT_REF"))
        assertFalse(command.contains("raw.githubusercontent.com"))
        assertFalse(command.contains("git clone"))
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
                "direct_port=51821|bypass_port=56004|cascade_listen_port=51822|" +
                "provision_port=9101|telemetry_port=9201|auto_ports=1",
        )
        assertEquals("/opt/ardtt", fields["install_dir"])
        assertEquals(51821, DeployInstallEnv.intField(fields, "direct_port"))
        assertEquals(56004, DeployInstallEnv.intField(fields, "bypass_port"))
        assertEquals(51822, DeployInstallEnv.intField(fields, "cascade_listen_port"))
        assertEquals(9101, DeployInstallEnv.intField(fields, "provision_port"))
        assertEquals(9201, DeployInstallEnv.intField(fields, "telemetry_port"))
        assertNull(DeployInstallEnv.intField(fields, "missing"))
        assertTrue(DeployInstallEnv.doneFields("ARDTT_WARN|x").isEmpty())
    }

    @Test
    fun packageRemotePathUsesArch() {
        assertEquals(
            "/opt/ardtt/incoming/ardtt-server-1.0.45-linux-arm64.tar.gz",
            DeployInstallEnv.packageRemotePath("1.0.45", "aarch64"),
        )
    }
}
