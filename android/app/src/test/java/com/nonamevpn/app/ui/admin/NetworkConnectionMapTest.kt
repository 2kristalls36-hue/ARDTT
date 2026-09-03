package com.nonamevpn.app.ui.admin

import com.nonamevpn.app.deploy.DeployHop
import com.nonamevpn.app.deploy.DeployTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkConnectionMapTest {
    private fun server(
        host: String,
        publicHost: String = "",
        cascadeEnabled: Boolean = false,
        cascadeHost: String = "",
        lastDeployedAtMs: Long = 0L,
        id: String = host,
    ) = DeployTarget(
        id = id,
        name = id,
        host = host,
        publicHost = publicHost,
        cascadeEnabled = cascadeEnabled,
        cascadeHost = cascadeHost,
        lastDeployedAtMs = lastDeployedAtMs,
    )

    @Test
    fun singleVpsShowsProviderAndVps() {
        val layout = buildNetworkMapLayout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = false,
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS),
            layout.titles,
        )
        assertFalse(layout.showCloudflare)
        assertEquals("45.129.2.3", layout.hops[1].knownHost)
    }

    @Test
    fun incognitoAddsCloudflareAndKeepsVpsAddress() {
        val layout = buildNetworkMapLayout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = true,
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS, NetworkMapCopy.CLOUDFLARE),
            layout.titles,
        )
        assertTrue(layout.showCloudflare)
        assertEquals("45.129.2.3", layout.hops[1].knownHost)
        assertNull(layout.hops.last().knownHost)
    }

    @Test
    fun cascadeShowsTwoVpsHops() {
        val layout = buildNetworkMapLayout(
            profileHost = "45.129.2.3",
            server = server(
                host = "45.129.2.3",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
            hideIp = false,
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS1, NetworkMapCopy.VPS2),
            layout.titles,
        )
        assertEquals("45.129.2.3", layout.hops[1].knownHost)
        assertEquals("2.26.125.160", layout.hops[2].knownHost)
        assertEquals("2.26.125.160", layout.vps2Host)
    }

    @Test
    fun cascadeIncognitoAddsCloudflareHop() {
        val layout = buildNetworkMapLayout(
            profileHost = "45.129.2.3",
            server = server(
                host = "45.129.2.3",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
            hideIp = true,
        )
        assertEquals(
            listOf(
                NetworkMapCopy.PROVIDER,
                NetworkMapCopy.VPS1,
                NetworkMapCopy.VPS2,
                NetworkMapCopy.CLOUDFLARE,
            ),
            layout.titles,
        )
    }

    @Test
    fun duplicateCascadeHostCollapsesToSingleVps() {
        val layout = buildNetworkMapLayout(
            profileHost = "45.129.2.3",
            server = server(
                host = "45.129.2.3",
                cascadeEnabled = true,
                cascadeHost = "45.129.2.3",
            ),
            hideIp = false,
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS),
            layout.titles,
        )
    }

    @Test
    fun noHostIsProviderOnly() {
        val layout = buildNetworkMapLayout(profileHost = null, server = null, hideIp = false)
        assertEquals(listOf(NetworkMapCopy.PROVIDER), layout.titles)
    }

    @Test
    fun incognitoWithoutHostStillAddsCloudflare() {
        val layout = buildNetworkMapLayout(profileHost = null, server = null, hideIp = true)
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.CLOUDFLARE),
            layout.titles,
        )
    }

    @Test
    fun matchingServerIgnoresUnrelatedLatestDeploy() {
        val servers = listOf(
            server(
                host = "9.9.9.9",
                cascadeEnabled = true,
                cascadeHost = "8.8.8.8",
                lastDeployedAtMs = 999L,
                id = "other",
            ),
            server(
                host = "45.129.2.3",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
                lastDeployedAtMs = 1L,
                id = "entry",
            ),
        )
        val match = findMatchingDeployServer(servers, "45.129.2.3")
        assertEquals("entry", match?.id)
        val layout = buildNetworkMapLayout("45.129.2.3", match, hideIp = false)
        assertEquals("2.26.125.160", layout.vps2Host)
    }

    @Test
    fun unmatchedProfileDoesNotInventCascade() {
        val servers = listOf(
            server(
                host = "9.9.9.9",
                cascadeEnabled = true,
                cascadeHost = "8.8.8.8",
                lastDeployedAtMs = 999L,
            ),
        )
        assertNull(findMatchingDeployServer(servers, "45.129.2.3"))
        val layout = buildNetworkMapLayout("45.129.2.3", null, hideIp = false)
        assertEquals(listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS), layout.titles)
        assertNull(layout.vps2Host)
    }

    @Test
    fun hopHostStripsPort() {
        assertEquals("45.129.2.3", hopHost("45.129.2.3:51820"))
        assertEquals("http://2.26.125.160:9100", provisionUrlForHost("2.26.125.160:22"))
    }

    @Test
    fun lastHopProvisionPrefersExit() {
        assertEquals(
            listOf("http://2.26.125.160:9100", "http://45.129.2.3:9100"),
            lastHopProvisionUrls("http://45.129.2.3:9100", "http://2.26.125.160:9100"),
        )
        assertEquals(
            listOf("http://45.129.2.3:9100"),
            lastHopProvisionUrls("http://45.129.2.3:9100", null),
        )
        assertEquals(
            "http://2.26.125.160:9100",
            DeployHop.exitProvisionUrl(
                server(
                    host = "45.129.2.3",
                    cascadeEnabled = true,
                    cascadeHost = "2.26.125.160",
                ),
            ),
        )
        assertNull(
            DeployHop.exitProvisionUrl(
                server(
                    host = "45.129.2.3",
                    cascadeEnabled = true,
                    cascadeHost = "45.129.2.3",
                ),
            ),
        )
    }

    @Test
    fun emptyOrDuplicateHopsAreNotShown() {
        assertFalse(
            shouldShowFilledHop(NetworkMapHopKind.Provider, ip = "", earlierIps = emptyList()),
        )
        assertFalse(
            shouldShowFilledHop(NetworkMapHopKind.Cloudflare, ip = "", earlierIps = emptyList()),
        )
        assertTrue(
            shouldShowFilledHop(
                NetworkMapHopKind.Vps,
                ip = "45.129.2.3",
                earlierIps = listOf("1.2.3.4"),
            ),
        )
        assertFalse(
            shouldShowFilledHop(
                NetworkMapHopKind.Cloudflare,
                ip = "45.129.2.3",
                earlierIps = listOf("45.129.2.3"),
            ),
        )
        assertTrue(
            shouldShowFilledHop(
                NetworkMapHopKind.Cloudflare,
                ip = "104.28.1.1",
                earlierIps = listOf("45.129.2.3"),
            ),
        )
    }
}
