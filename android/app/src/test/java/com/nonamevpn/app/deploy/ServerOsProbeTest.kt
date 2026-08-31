package com.nonamevpn.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerOsProbeTest {
    @Test
    fun parseUsesPrettyNameWhenAvailable() {
        val parsed = ServerOsProbe.parse(
            """
            NAME="Ubuntu"
            VERSION_ID="24.04"
            PRETTY_NAME="Ubuntu 24.04.1 LTS"
            ID=ubuntu
            """.trimIndent(),
        )
        assertEquals("ubuntu", parsed.osId)
        assertEquals("Ubuntu 24.04.1 LTS", parsed.osVersionLabel)
        assertTrue(parsed.isRecognized)
    }

    @Test
    fun parseFallsBackToNameAndVersionId() {
        val parsed = ServerOsProbe.parse(
            """
            NAME=Fedora
            VERSION_ID=40
            ID=fedora
            """.trimIndent(),
        )
        assertEquals("fedora", parsed.osId)
        assertEquals("Fedora 40", parsed.osVersionLabel)
        assertTrue(parsed.isRecognized)
    }

    @Test
    fun unknownIdIsNotRecognized() {
        val parsed = ServerOsProbe.parse(
            """
            NAME=NixOS
            VERSION_ID=24.05
            ID=nixos
            """.trimIndent(),
        )
        assertEquals("nixos", parsed.osId)
        assertEquals("NixOS 24.05", parsed.osVersionLabel)
        assertFalse(parsed.isRecognized)
        assertFalse(isRecognizedServerOsId("nixos"))
    }
}
