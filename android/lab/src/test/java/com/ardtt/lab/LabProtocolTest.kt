package com.ardtt.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class LabProtocolTest {
    @Test
    fun parseCommandLine() {
        val req = LabProtocol.parseLine("""{"id":"7","cmd":"probe","vps":"1.2.3.4","port":9100}""")
        checkNotNull(req)
        assertEquals("7", req.id)
        assertEquals("probe", req.cmd)
        assertEquals("1.2.3.4", req.fields.getString("vps"))
        assertEquals(9100, req.fields.getInt("port"))
    }

    @Test
    fun rejectEmptyAndNonJson() {
        assertNull(LabProtocol.parseLine("  "))
        assertNull(LabProtocol.parseLine("not-json"))
        assertNull(LabProtocol.parseLine("""{"id":"1"}"""))
    }

    @Test
    fun replyAndErrorAreJsonLines() {
        val ok = JSONObject(LabProtocol.reply("1", true, mapOf("pong" to true)))
        assertEquals("1", ok.getString("id"))
        assertTrue(ok.getBoolean("ok"))
        assertTrue(ok.getBoolean("pong"))
        val err = JSONObject(LabProtocol.error("2", "нет LTE"))
        assertFalse(err.getBoolean("ok"))
        assertEquals("нет LTE", err.getString("error"))
    }
}
