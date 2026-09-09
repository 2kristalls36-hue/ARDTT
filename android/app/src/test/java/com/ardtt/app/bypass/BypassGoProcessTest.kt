package com.ardtt.app.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassGoProcessTest {
    @Test
    fun rawBoxMissingMtuDefaultsToCascadeSafe() {
        val box = """
            IP = 10.9.0.3
            DNS = 1.1.1.1
        """.trimIndent()
        val conf = BypassGoProcess.parseRawBox(box)
        assertNotNull(conf)
        assertEquals(1280, conf!!.mtu)
    }

    @Test
    fun parsesRawBox() {
        val box = """
            IP = 10.9.0.3
            DNS = 1.1.1.1,8.8.8.8
            MTU = 1300
        """.trimIndent()
        val conf = BypassGoProcess.parseRawBox(box)
        assertNotNull(conf)
        assertEquals("10.9.0.3", conf!!.ip)
        assertEquals("1.1.1.1,8.8.8.8", conf.dnsCsv)
        assertEquals(1300, conf.mtu)
    }

    @Test
    fun parsesRawConfLineWithExtraFields() {
        val conf = BypassGoProcess.parseRawConfLine("RAWCONF:10.9.0.5|1.1.1.1|1280|gen=2")
        assertNotNull(conf)
        assertEquals("10.9.0.5", conf!!.ip)
        assertEquals(1280, conf.mtu)
    }

    @Test
    fun parsesControlAck() {
        val ack = BypassGoProcess.parseControlAck("V1|k3|1|ACK|DETACH_TUN|ok")
        assertNotNull(ack)
        assertEquals("k3", ack!!.reqId)
        assertNull(BypassGoProcess.parseControlAck("PAUSE"))
    }

    @Test
    fun staleControlAckIsFailure() {
        assertTrue(BypassGoProcess.controlAckSucceeded("V1|k3|1|ACK|ATTACH_TUN|ok"))
        assertFalse(BypassGoProcess.controlAckSucceeded("V1|k3|2|ACK|ATTACH_TUN|stale"))
        assertFalse(BypassGoProcess.controlAckSucceeded("V1|k3|1|ACK|ATTACH_TUN|err|timeout"))
        assertFalse(BypassGoProcess.controlAckSucceeded(null))
    }

    @Test
    fun rawConfLineBlankDnsFallsBackToGateway() {
        val conf = BypassGoProcess.parseRawConfLine("RAWCONF:10.9.0.5||1280")
        assertNotNull(conf)
        assertEquals("10.9.0.1", conf!!.dnsCsv)
    }

    @Test
    fun classifyFatalMapsDeadCallGoLogs() {
        val msg = BypassGoProcess.classifyFatal(
            "[STREAM 1] CALL_UNAVAILABLE: VK call is unavailable (error_code=951)",
        )
        assertEquals(DEAD_CALL_USER_MESSAGE, msg)
        assertEquals(
            DEAD_CALL_USER_MESSAGE,
            BypassGoProcess.classifyFatal(
                "[STREAM 1] [VK Auth] VK Calls path returned non-retryable call error: " +
                    "VK call is unavailable (error_code=951)",
            ),
        )
        assertNull(
            BypassGoProcess.classifyFatal(
                "Обход недоступен: нет активных каналов. Проверьте код звонка и сеть.",
            ),
        )
    }

    @Test
    fun turnTcpFlagOnlyWhenRequested() {
        assertEquals(listOf("-turn-tcp"), bypassTurnTransportArgs(true))
        assertEquals(emptyList<String>(), bypassTurnTransportArgs(false))
    }

    @Test
    fun stateDirLivesUnderAppFilesNotNativeLib() {
        val files = java.io.File("/data/user/0/com.ardtt.app/files")
        val dir = bypassGoStateDir(files)
        assertEquals("bypass", dir.name)
        assertEquals(files, dir.parentFile)
        assertEquals("ARDTT_STATE_DIR", BypassGoProcess.STATE_DIR_ENV)
    }
}
