package com.nonamevpn.app.ui.admin

import com.nonamevpn.app.core.ConnState
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

    private fun layout(
        profileHost: String?,
        server: DeployTarget?,
        hideIp: Boolean,
        sessionUp: Boolean = true,
        observedLastHop: String? = null,
        liveCascadeHost: String? = null,
    ) = buildNetworkMapLayout(
        profileHost = profileHost,
        server = server,
        hideIp = hideIp,
        sessionUp = sessionUp,
        observedLastHop = observedLastHop,
        liveCascadeHost = liveCascadeHost,
    )

    @Test
    fun disconnectedHidesVpsEvenWhenProfileHasHost() {
        val built = layout(
            profileHost = "45.129.2.3",
            server = server(
                host = "45.129.2.3",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
            hideIp = true,
            sessionUp = false,
        )
        assertEquals(listOf(NetworkMapCopy.PROVIDER), built.titles)
        assertNull(built.vps1Host)
        assertNull(built.vps2Host)
        assertFalse(built.showCloudflare)
    }

    @Test
    fun pausedOrIdleDoesNotShowVpnHops() {
        assertFalse(networkMapShowsVpnHops(ConnState.Idle))
        assertFalse(networkMapShowsVpnHops(ConnState.Ready))
        assertFalse(networkMapShowsVpnHops(ConnState.Probing))
        assertFalse(networkMapShowsVpnHops(ConnState.Connecting))
        assertFalse(networkMapShowsVpnHops(ConnState.Disconnecting))
        assertFalse(networkMapShowsVpnHops(ConnState.PausedTrustedWifi))
        assertFalse(networkMapShowsVpnHops(ConnState.Error))
        assertTrue(networkMapShowsVpnHops(ConnState.Connected))
    }

    @Test
    fun singleVpsShowsProviderAndVps() {
        val built = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = false,
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS),
            built.titles,
        )
        assertFalse(built.showCloudflare)
        assertEquals("45.129.2.3", built.hops[1].knownHost)
    }

    @Test
    fun incognitoAddsCloudflareAndKeepsVpsAddress() {
        val built = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = true,
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS, NetworkMapCopy.CLOUDFLARE),
            built.titles,
        )
        assertTrue(built.showCloudflare)
        assertEquals("45.129.2.3", built.hops[1].knownHost)
        assertNull(built.hops.last().knownHost)
    }

    @Test
    fun cascadeShowsTwoVpsHops() {
        val built = layout(
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
            built.titles,
        )
        assertEquals("45.129.2.3", built.hops[1].knownHost)
        assertEquals("2.26.125.160", built.hops[2].knownHost)
        assertEquals("2.26.125.160", built.vps2Host)
    }

    @Test
    fun cascadeFromLiveLastHopWhenDeployHasNoFlag() {
        val built = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = false,
            observedLastHop = "2.26.125.160",
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS1, NetworkMapCopy.VPS2),
            built.titles,
        )
        assertEquals("2.26.125.160", built.vps2Host)
    }

    @Test
    fun liveLastHopSameAsEntryStaysSingleVps() {
        val built = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = false,
            observedLastHop = "45.129.2.3",
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS),
            built.titles,
        )
        assertNull(built.vps2Host)
    }

    @Test
    fun cloudflareLastHopIsNotVps2() {
        assertFalse(lastHopCanBeVps2("45.129.2.3", "104.16.132.229"))
        val built = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = true,
            observedLastHop = "104.16.132.229",
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS, NetworkMapCopy.CLOUDFLARE),
            built.titles,
        )
        assertNull(built.vps2Host)
    }

    @Test
    fun cascadeIncognitoAddsCloudflareHop() {
        val built = layout(
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
            built.titles,
        )
    }

    @Test
    fun duplicateCascadeHostCollapsesToSingleVps() {
        val built = layout(
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
            built.titles,
        )
    }

    @Test
    fun noHostIsProviderOnly() {
        val built = layout(profileHost = null, server = null, hideIp = false)
        assertEquals(listOf(NetworkMapCopy.PROVIDER), built.titles)
    }

    @Test
    fun incognitoWithoutHostStillAddsCloudflareWhenConnected() {
        val built = layout(profileHost = null, server = null, hideIp = true)
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.CLOUDFLARE),
            built.titles,
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
        val built = layout("45.129.2.3", match, hideIp = false)
        assertEquals("2.26.125.160", built.vps2Host)
    }

    @Test
    fun matchingServerUnionsPublicHostAndSshHost() {
        val servers = listOf(
            server(
                host = "10.0.0.1",
                publicHost = "45.129.2.3",
                cascadeEnabled = false,
                lastDeployedAtMs = 1L,
                id = "old-public",
            ),
            server(
                host = "45.129.2.3",
                publicHost = "",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
                lastDeployedAtMs = 99L,
                id = "cascade-entry",
            ),
        )
        val match = findMatchingDeployServer(servers, "45.129.2.3")
        assertEquals("cascade-entry", match?.id)
        val built = layout("45.129.2.3", match, hideIp = false)
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS1, NetworkMapCopy.VPS2),
            built.titles,
        )
    }

    @Test
    fun matchingPrefersCascadeEvenIfOlderPlainCardWasDeployedLater() {
        val servers = listOf(
            server(
                host = "10.0.0.1",
                publicHost = "45.129.2.3",
                cascadeEnabled = false,
                lastDeployedAtMs = 99L,
                id = "plain-newer",
            ),
            server(
                host = "45.129.2.3",
                publicHost = "",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
                lastDeployedAtMs = 1L,
                id = "cascade-older",
            ),
        )
        val match = findMatchingDeployServer(servers, "45.129.2.3")
        assertEquals("cascade-older", match?.id)
        val built = layout("45.129.2.3", match, hideIp = false)
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS1, NetworkMapCopy.VPS2),
            built.titles,
        )
    }

    @Test
    fun liveCascadeHostAddsVps2WithoutDeployFlag() {
        val built = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = false,
            liveCascadeHost = "2.26.125.160",
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS1, NetworkMapCopy.VPS2),
            built.titles,
        )
        assertEquals("2.26.125.160", built.vps2Host)
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
        val built = layout("45.129.2.3", null, hideIp = false)
        assertEquals(listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS), built.titles)
        assertNull(built.vps2Host)
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
