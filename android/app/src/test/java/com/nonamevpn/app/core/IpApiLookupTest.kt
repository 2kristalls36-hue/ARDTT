package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpApiLookupTest {
    @Test
    fun parsesSuccessResponse() {
        val body = """
            {
              "status": "success",
              "query": "157.1.2.3",
              "isp": "M247 Ltd",
              "city": "New York",
              "country": "United States",
              "countryCode": "US"
            }
        """.trimIndent()
        val info = IpApiLookup.parseResponse(body)
        assertEquals("157.1.2.3", info.ip)
        assertEquals("M247 Ltd · New York, US", info.subtitle)
        assertNull(info.error)
    }

    @Test
    fun parsesFailureResponse() {
        val body = """{"status":"fail","message":"private range"}"""
        val info = IpApiLookup.parseResponse(body)
        assertEquals("", info.ip)
        assertEquals("private range", info.error)
    }
}
