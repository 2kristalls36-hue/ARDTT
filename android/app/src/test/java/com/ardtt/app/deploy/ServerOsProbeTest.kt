package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(ServerOsMark.Ubuntu, serverOsMark(parsed.osId))
        assertEquals("Ubuntu", serverOsBadgeLabel(parsed.osId))
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
        assertEquals(ServerOsMark.Linux, serverOsMark("nixos"))
    }

    @Test
    fun parseReadsQuotedIdAndIdLikeAliases() {
        val parsed = ServerOsProbe.parse(
            """
            NAME='Pop!_OS'
            ID='pop'
            ID_LIKE="ubuntu debian"
            PRETTY_NAME='Pop!_OS 22.04 LTS'
            """.trimIndent(),
        )
        assertEquals("ubuntu", parsed.osId)
        assertEquals("Pop!_OS 22.04 LTS", parsed.osVersionLabel)
        assertTrue(parsed.isRecognized)
        assertEquals(ServerOsMark.Ubuntu, serverOsMark(parsed.osId))
    }

    @Test
    fun parseIgnoresMotdNoiseAroundOsRelease() {
        val parsed = ServerOsProbe.parse(
            """
            Welcome to Ubuntu 24.04 LTS
            ID=ubuntu
            PRETTY_NAME="Ubuntu 24.04.1 LTS"
            Last login: Thu
            """.trimIndent(),
        )
        assertEquals("ubuntu", parsed.osId)
        assertEquals("Ubuntu 24.04.1 LTS", parsed.osVersionLabel)
    }

    @Test
    fun blankOsShowsUnknownMarkAndOsLabel() {
        assertEquals(ServerOsMark.Unknown, serverOsMark(""))
        assertEquals("OS", serverOsBadgeLabel(""))
        assertFalse(isRecognizedServerOsId(""))
    }

    @Test
    fun ubuntuStaysUbuntuAndDoesNotFallBackToGenericLinux() {
        assertEquals(ServerOsMark.Ubuntu, serverOsMark("ubuntu"))
        assertEquals(ServerOsMark.Ubuntu, serverOsMark("Ubuntu"))
        assertEquals(ServerOsMark.Debian, serverOsMark("debian"))
        assertEquals(ServerOsMark.Linux, serverOsMark("nixos"))
        assertEquals(ServerOsMark.Unknown, serverOsMark(""))
    }

    @Test
    fun alpineArchRhelAndSuseKeepDistinctMarks() {
        assertEquals(ServerOsMark.Alpine, serverOsMark("alpine"))
        assertEquals(ServerOsMark.Arch, serverOsMark("arch"))
        assertEquals(ServerOsMark.Rhel, serverOsMark("rhel"))
        assertEquals(ServerOsMark.Suse, serverOsMark("opensuse"))
        assertEquals(ServerOsMark.Fedora, serverOsMark("fedora"))
        assertEquals(ServerOsMark.Centos, serverOsMark("centos"))
    }

    @Test
    fun centosUsesOwnMarkWhileRhelFamilyStaysRhel() {
        assertEquals(ServerOsMark.Centos, serverOsMark("centos"))
        assertEquals(ServerOsMark.Rhel, serverOsMark("rhel"))
        assertEquals(ServerOsMark.Rhel, serverOsMark("rocky"))
        assertEquals(ServerOsMark.Rhel, serverOsMark("almalinux"))
        assertEquals(ServerOsMark.Arch, serverOsMark("arch"))
        assertEquals(ServerOsMark.Fedora, serverOsMark("fedora"))
    }

    @Test
    fun appliedToSkipsUnchangedAndEmpty() {
        val target = DeployTarget(id = "1", name = "s", host = "10.0.0.1")
        assertNull(ServerOsInfo("", "").appliedTo(target))
        val next = ServerOsInfo("ubuntu", "Ubuntu 24.04.1 LTS").appliedTo(target)
        assertEquals("ubuntu", next?.osId)
        assertEquals("Ubuntu 24.04.1 LTS", next?.osVersion)
        assertNull(ServerOsInfo("ubuntu", "Ubuntu 24.04.1 LTS").appliedTo(next!!))
    }
}
