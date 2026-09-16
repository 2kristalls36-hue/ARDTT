package com.ardtt.app.core

import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.DeployTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun hopCacheReusesUntilForceOrKeyChange() {
        IpApiLookup.clearPublicIpHopCache()
        val underlay = IpApiInfo(ip = "203.0.113.10", subtitle = "ISP")
        val tunnel = IpApiInfo(ip = "104.16.1.1", subtitle = "Cloudflare")
        IpApiLookup.rememberUnderlayHop("wifi:home", underlay)
        IpApiLookup.rememberTunnelHop("warp|dev", tunnel)

        assertEquals(underlay, IpApiLookup.peekUnderlayHop("wifi:home", force = false))
        assertEquals(tunnel, IpApiLookup.peekTunnelHop("warp|dev", force = false))
        assertNull(IpApiLookup.peekUnderlayHop("wifi:home", force = true))
        assertNull(IpApiLookup.peekUnderlayHop("cell:1", force = false))
        assertNull(IpApiLookup.peekTunnelHop("direct|dev", force = false))

        IpApiLookup.clearPublicIpHopCache()
        assertNull(IpApiLookup.peekUnderlayHop("wifi:home", force = false))
        assertNull(IpApiLookup.peekTunnelHop("warp|dev", force = false))
    }

    @Test
    fun tunnelAndNetworkMapShareTheSameHopKeys() {
        val server = DeployTarget(
            id = "entry",
            name = "entry",
            host = "45.129.2.3",
            cascadeEnabled = true,
            cascadeHost = "2.26.125.160",
            cascadeProvisionPort = 9200,
        )
        val tunnelExit = DeployHop.exitProvisionUrl(server)
        val mapExit = DeployHop.warpExitProvisionUrl(
            server,
            liveExitHost = "2.26.125.160",
        )
        val shared = IpApiLookup.tunnelHopKey(
            hideIp = true,
            provisionBaseUrl = "http://45.129.2.3:9100",
            exitProvisionBaseUrl = tunnelExit,
            deviceId = "dev",
            viaVpn = true,
        )
        assertEquals(
            shared,
            IpApiLookup.tunnelHopKey(
                hideIp = true,
                provisionBaseUrl = "http://45.129.2.3:9100",
                exitProvisionBaseUrl = mapExit,
                deviceId = "dev",
                viaVpn = true,
            ),
        )
        assertNotEquals(
            shared,
            IpApiLookup.tunnelHopKey(
                hideIp = true,
                provisionBaseUrl = "http://45.129.2.3:9100",
                exitProvisionBaseUrl = "http://2.26.125.160:9100",
                deviceId = "dev",
                viaVpn = true,
            ),
        )
        assertEquals("wifi:home", IpApiLookup.underlayHopKey("wifi:home"))
    }

    @Test
    fun addressCacheReusesUntilForceOrClear() {
        IpApiLookup.clearPublicIpHopCache()
        val geo = IpApiInfo(ip = "2.26.125.160", subtitle = "VPS · Ashburn, US")
        IpApiLookup.rememberAddress("2.26.125.160", geo)

        assertEquals(geo, IpApiLookup.peekAddress("2.26.125.160", force = false))
        assertNull(IpApiLookup.peekAddress("2.26.125.160", force = true))
        assertNull(IpApiLookup.peekAddress("8.8.8.8", force = false))

        IpApiLookup.clearPublicIpHopCache()
        assertNull(IpApiLookup.peekAddress("2.26.125.160", force = false))
    }
}
