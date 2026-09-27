package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProvisionAdminTokenTest {
    private val hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun normalizeKeepsCaseAndTrims() {
        assertEquals(hex, ProvisionAdminToken.normalize("  $hex\n"))
        assertEquals("AbC-token_value.16", ProvisionAdminToken.normalize("AbC-token_value.16"))
        assertNull(ProvisionAdminToken.normalize("short"))
        assertNull(ProvisionAdminToken.normalize("has space and more than sixteen"))
        assertNull(ProvisionAdminToken.normalize(""))
    }

    @Test
    fun fromRemoteOutputIgnoresStderrNoise() {
        val out = """
            sudo: unable to resolve host tpzcpbyoeg
            ARDTT_ADMIN_TOKEN $hex
        """.trimIndent()
        assertEquals(hex, ProvisionAdminToken.fromRemoteOutput(out))
        assertNull(ProvisionAdminToken.fromRemoteOutput("sudo: unable to resolve host"))
    }

    @Test
    fun forBaseMatchesProvisionHost() {
        val token = hex
        val servers = listOf(
            DeployTarget(
                id = "1",
                name = "edge",
                host = "203.0.113.10",
                publicHost = "203.0.113.10",
                provisionPort = 9100,
                provisionAdminToken = "  $token",
            ),
        )
        assertEquals(token, ProvisionAdminToken.forBase(servers, "http://203.0.113.10:9100/"))
        assertEquals("", ProvisionAdminToken.forBase(servers, "http://203.0.113.11:9100"))
    }
}
