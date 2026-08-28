package com.nonamevpn.app.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BypassGoProcessTest {
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
    fun parsesRawConfLine() {
        val conf = BypassGoProcess.parseRawConfLine("RAWCONF:10.9.0.5|1.1.1.1|1280")
        assertNotNull(conf)
        assertEquals("10.9.0.5", conf!!.ip)
        assertEquals("1.1.1.1", conf.dnsCsv)
        assertEquals(1280, conf.mtu)
    }

    @Test
    fun rawConfLineBlankDnsFallsBackToGateway() {
        val conf = BypassGoProcess.parseRawConfLine("RAWCONF:10.9.0.5||1280")
        assertNotNull(conf)
        assertEquals("10.9.0.1", conf!!.dnsCsv)
    }
}
