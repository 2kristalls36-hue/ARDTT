package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployProtocol2Test {
    @Test
    fun jsonProgressUpdatesStep() {
        val line = """{"protocol":2,"type":"progress","progress":0.42,"message":"слои","phase":"fetch"}"""
        val got = DeployInstallEnv.protocol2Progress(line)
        assertEquals(0.42f, got!!.first, 0.0001f)
        assertEquals("слои", got.second)
    }

    @Test
    fun jsonErrorKeepsCodeForm() {
        val line = """{"protocol":2,"type":"error","code":"DISK_FULL","message":"мало места"}"""
        assertEquals("code=DISK_FULL|мало места", DeployInstallEnv.protocol2Error(line))
    }

    @Test
    fun jsonDoneFieldsFromObject() {
        val line = """{"protocol":2,"type":"done","done":{"direct_port":"51821","deploy_version":"1.0.54"}}"""
        val fields = DeployInstallEnv.doneFields(line)
        assertEquals("51821", fields["direct_port"])
        assertEquals("1.0.54", fields["deploy_version"])
        assertEquals(51821, DeployInstallEnv.intField(fields, "direct_port"))
    }

    @Test
    fun legacyStillParsedAndUnknownJsonIgnored() {
        val legacy = DeployInstallEnv.doneFields(
            "ARDTT_DONE|install_dir=/opt/ardtt|direct_port=51820",
        )
        assertEquals("/opt/ardtt", legacy["install_dir"])
        assertNull(DeployInstallEnv.protocol2Progress("ARDTT_PROGRESS|0.1|hi"))
        assertNull(DeployInstallEnv.protocol2Error("""{"protocol":1,"type":"error","message":"x"}"""))
        assertNull(DeployInstallEnv.protocol2Progress("""{"not":"an event"}"""))
        assertTrue(DeployInstallEnv.doneFields("ARDTT_WARN|x").isEmpty())
    }

    @Test
    fun exitZeroWithoutDoneIsNotSuccess() {
        assertTrue(DeployInstallEnv.missingDonePayload(0, emptyMap(), null))
        assertFalse(DeployInstallEnv.missingDonePayload(0, mapOf("direct_port" to "51820"), null))
        assertFalse(DeployInstallEnv.missingDonePayload(1, emptyMap(), null))
        assertFalse(DeployInstallEnv.missingDonePayload(0, emptyMap(), "code=BUSY|x"))
    }

    @Test
    fun malformedAndUnknownProtocolAreIgnored() {
        assertNull(DeployInstallEnv.protocol2Progress("{not json"))
        assertNull(DeployInstallEnv.protocol2Error("""{"protocol":2,"type":"nope"}"""))
        assertNull(DeployInstallEnv.protocol2DoneFields("""{"protocol":2,"type":"error","code":"X"}"""))
        assertTrue(DeployInstallEnv.doneFields("""{"protocol":2,"type":"done"}""").isEmpty())
    }
}
