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

    @Test
    fun lookupEndpointUsesIpPath() {
        assertEquals(
            "http://ip-api.com/json/1.2.3.4?fields=status,message,query,isp,city,country,countryCode",
            IpApiLookup.lookupEndpoint("1.2.3.4"),
        )
    }

    @Test
    fun friendlyErrorHidesBindFailures() {
        assertEquals(
            "Не удалось определить IP",
            IpApiLookup.friendlyError("Binding socket to network 634 failed: EPERM (Operation not permitted)"),
        )
    }

    @Test
    fun pickUnderlayIpSkipsCloudflareAndPrefersCache() {
        EgressIpProbe.clear()
        EgressIpProbe.invalidateUnderlay()
        val failed = IpApiInfo(ip = "", subtitle = "", error = "Не удалось определить IP")
        assertEquals(
            "9.9.9.9",
            IpApiLookup.pickUnderlayIp(failed, probedIp = "9.9.9.9", cachedIp = "1.2.3.4"),
        )
        assertEquals(
            "1.2.3.4",
            IpApiLookup.pickUnderlayIp(failed, probedIp = "104.28.198.244", cachedIp = "1.2.3.4"),
        )
        assertNull(
            IpApiLookup.pickUnderlayIp(failed, probedIp = "104.28.198.244", cachedIp = null),
        )
        assertNull(
            IpApiLookup.pickUnderlayIp(
                IpApiInfo(ip = "2.26.125.160", subtitle = "VPS"),
                probedIp = null,
                cachedIp = null,
                rejectIps = listOf("2.26.125.160"),
            ),
        )
        assertEquals(
            "203.0.113.10",
            IpApiLookup.pickUnderlayIp(
                IpApiInfo(ip = "203.0.113.10", subtitle = "ISP"),
                probedIp = "9.9.9.9",
                cachedIp = "1.2.3.4",
            ),
        )
    }
}
