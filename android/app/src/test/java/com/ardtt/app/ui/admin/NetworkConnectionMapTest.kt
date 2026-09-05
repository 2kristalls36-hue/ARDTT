package com.ardtt.app.ui.admin

import androidx.compose.ui.graphics.Color
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.IpApiInfo
import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.core.VpnPath
import com.ardtt.app.ui.theme.ArdttColors
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
        liveCascade: ProvisionAdminApi.LiveCascadeInfo? = null,
    ) = buildNetworkMapLayout(
        profileHost = profileHost,
        server = server,
        hideIp = hideIp,
        sessionUp = sessionUp,
        liveCascade = liveCascade,
    )

    private fun live(enabled: Boolean, host: String? = null) =
        ProvisionAdminApi.LiveCascadeInfo(enabled = enabled, host = host)

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
    fun liveHealthOffIgnoresStaleCascadeCard() {
        val built = layout(
            profileHost = "159.194.225.162",
            server = server(
                host = "159.194.225.162",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
            hideIp = false,
            liveCascade = live(enabled = false, host = "2.26.125.160"),
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS),
            built.titles,
        )
        assertNull(built.vps2Host)
    }

    @Test
    fun cloudflareExitIsNotVps2() {
        assertFalse(usableAsVps2("45.129.2.3", "104.16.132.229"))
        val built = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = true,
            liveCascade = live(enabled = true, host = "104.16.132.229"),
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
    fun matchingPrefersNewestStandaloneOverOlderCascade() {
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
        assertEquals("plain-newer", match?.id)
        val built = layout("45.129.2.3", match, hideIp = false)
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS),
            built.titles,
        )
        assertNull(built.vps2Host)
    }

    @Test
    fun liveHealthHostAddsVps2WithoutDeployFlag() {
        val built = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = false,
            liveCascade = live(enabled = true, host = "2.26.125.160"),
        )
        assertEquals(
            listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS1, NetworkMapCopy.VPS2),
            built.titles,
        )
        assertEquals("2.26.125.160", built.vps2Host)
    }

    @Test
    fun healthOnWithoutExitHostStaysSingleVps() {
        val built = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = false,
            liveCascade = live(enabled = true),
        )
        assertEquals(listOf(NetworkMapCopy.PROVIDER, NetworkMapCopy.VPS), built.titles)
        assertNull(built.vps2Host)
    }

    @Test
    fun healthOnWithoutHostUsesDeployCard() {
        val built = layout(
            profileHost = "45.129.2.3",
            server = server(
                host = "45.129.2.3",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
            hideIp = false,
            liveCascade = live(enabled = true),
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
        assertTrue(
            shouldShowFilledHop(NetworkMapHopKind.Provider, ip = "", earlierIps = emptyList()),
        )
        assertFalse(
            shouldShowFilledHop(NetworkMapHopKind.Cloudflare, ip = "", earlierIps = emptyList()),
        )
        assertTrue(
            shouldShowFilledHop(
                kind = NetworkMapHopKind.Cloudflare,
                ip = "",
                earlierIps = emptyList(),
                terminal = true,
            ),
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
        assertFalse(
            shouldShowFilledHop(
                kind = NetworkMapHopKind.Cloudflare,
                ip = "45.129.2.3",
                earlierIps = listOf("45.129.2.3"),
                terminal = true,
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

    @Test
    fun providerCardShowsErrorWhenIpIsMissing() {
        assertEquals(
            "Не удалось определить IP",
            hopCardPrimaryText(IpApiInfo.Empty),
        )
        assertEquals(
            "Не удалось определить IP",
            hopCardPrimaryText(IpApiInfo(ip = "", subtitle = "", error = "Не удалось определить IP")),
        )
        assertEquals(
            "8.8.8.8",
            hopCardPrimaryText(IpApiInfo(ip = "8.8.8.8", subtitle = "ISP · City, US")),
        )
        assertEquals(
            "Определение…",
            hopCardPrimaryText(IpApiInfo.Empty, loading = true),
        )
        assertEquals(
            "8.8.8.8",
            hopCardPrimaryText(IpApiInfo(ip = "8.8.8.8", subtitle = ""), loading = true),
        )
    }

    @Test
    fun greenCardFollowsSettingsEgressHop() {
        val standalone = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = false,
        )
        assertEquals(NetworkMapHopKind.Vps, terminalHopKind(standalone.hops))
        assertFalse(hopCardHighlighted(NetworkMapHopKind.Provider, terminalHopKind(standalone.hops)))
        assertTrue(hopCardHighlighted(NetworkMapHopKind.Vps, terminalHopKind(standalone.hops)))

        val cascade = layout(
            profileHost = "45.129.2.3",
            server = server(
                host = "45.129.2.3",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
            hideIp = false,
        )
        val cascadeTerm = terminalHopKind(cascade.hops)
        assertEquals(NetworkMapHopKind.Vps2, cascadeTerm)
        assertFalse(hopCardHighlighted(NetworkMapHopKind.Vps1, cascadeTerm))
        assertTrue(hopCardHighlighted(NetworkMapHopKind.Vps2, cascadeTerm))

        val hideIp = layout(
            profileHost = "45.129.2.3",
            server = server(
                host = "45.129.2.3",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
            hideIp = true,
        )
        val hideTerm = terminalHopKind(hideIp.hops)
        assertEquals(NetworkMapHopKind.Cloudflare, hideTerm)
        assertFalse(hopCardHighlighted(NetworkMapHopKind.Vps2, hideTerm))
        assertTrue(hopCardHighlighted(NetworkMapHopKind.Cloudflare, hideTerm))

        val down = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = true,
            sessionUp = false,
        )
        assertNull(terminalHopKind(down.hops))
        assertFalse(hopCardHighlighted(NetworkMapHopKind.Provider, terminalHopKind(down.hops)))
    }

    @Test
    fun hopMapGrayStrokeDropsBlueCastAndMatchesCardRim() {
        val outline = Color(0xFFB2C2D7)
        val gray = hopMapGrayStroke(outline)
        assertEquals(gray.red, gray.green, 1e-5f)
        assertEquals(gray.green, gray.blue, 1e-5f)
        assertEquals(gray, hopCardStrokeColor(highlighted = false, outline = outline, connected = ArdttColors.connected))
        assertEquals(
            ArdttColors.connected,
            hopCardStrokeColor(highlighted = true, outline = outline, connected = ArdttColors.connected),
        )
    }


    @Test
    fun hopCardAccentFollowsLiveVpnPath() {
        assertEquals(ArdttColors.connected, hopCardAccentColor(VpnPath.Direct))
        assertEquals(ArdttColors.connected, hopCardAccentColor(null))
        assertEquals(ArdttColors.pathBypass, hopCardAccentColor(VpnPath.Bypass))
        assertEquals(
            ArdttColors.pathBypass,
            hopCardStrokeColor(
                highlighted = true,
                outline = Color(0xFFB2C2D7),
                connected = hopCardAccentColor(VpnPath.Bypass),
            ),
        )
    }

    @Test
    fun hopPingUsesEntryForVpsAndExitForVps2() {
        val pings = HopHealthPings(entryMs = 12L, exitMs = 40L)
        assertEquals(12L, hopHealthPingMs(NetworkMapHopKind.Vps, pings))
        assertEquals(12L, hopHealthPingMs(NetworkMapHopKind.Vps1, pings))
        assertEquals(40L, hopHealthPingMs(NetworkMapHopKind.Vps2, pings))
        assertEquals(-1L, hopHealthPingMs(NetworkMapHopKind.Provider, pings))
        assertEquals(-1L, hopHealthPingMs(NetworkMapHopKind.Cloudflare, pings))
        assertEquals("12 мс", hopPingLabel(NetworkMapHopKind.Vps, pings))
        assertEquals("40 мс", hopPingLabel(NetworkMapHopKind.Vps2, pings))
        assertEquals("", hopPingLabel(NetworkMapHopKind.Provider, pings))
        assertEquals("", hopPingLabel(NetworkMapHopKind.Vps, HopHealthPings()))
        assertEquals("", hopPingLabel(NetworkMapHopKind.Vps2, HopHealthPings(entryMs = 12L)))
    }

    @Test
    fun cloudflareTitlePutsMarkBetweenIpAndName() {
        val cloudflare = hopTitleLayout(NetworkMapHopKind.Cloudflare, NetworkMapCopy.CLOUDFLARE)
        assertEquals(NetworkMapCopy.CLOUDFLARE_IP, cloudflare.leadingText)
        assertTrue(cloudflare.showCloudflareMark)
        assertEquals(NetworkMapCopy.CLOUDFLARE_NAME, cloudflare.trailingText)

        val vps = hopTitleLayout(NetworkMapHopKind.Vps, NetworkMapCopy.VPS)
        assertEquals(NetworkMapCopy.VPS, vps.leadingText)
        assertFalse(vps.showCloudflareMark)
        assertEquals("", vps.trailingText)
    }

    @Test
    fun keepCardsThroughConnectingButClearAfterDisconnect() {
        assertTrue(networkMapKeepCards(ConnState.Connected))
        assertTrue(networkMapKeepCards(ConnState.Connecting))
        assertFalse(networkMapKeepCards(ConnState.Disconnecting))
        assertFalse(networkMapKeepCards(ConnState.Ready))
        assertFalse(networkMapKeepCards(ConnState.Idle))
        assertFalse(networkMapKeepCards(ConnState.PausedTrustedWifi))
        assertFalse(shouldClearNetworkMapCards(ConnState.Connected, ConnState.Connecting))
        assertTrue(shouldClearNetworkMapCards(ConnState.Connected, ConnState.Disconnecting))
        assertTrue(shouldClearNetworkMapCards(ConnState.Connecting, ConnState.Ready))
        assertFalse(shouldClearNetworkMapCards(ConnState.Ready, ConnState.Connected))
    }

    @Test
    fun skipAutoloadWhenSameSessionAlreadyHasCards() {
        val hops = listOf(filledProvider())
        val key = cacheKey()
        assertTrue(
            shouldSkipNetworkMapAutoload(
                state = ConnState.Connected,
                storedKey = key,
                currentKey = key,
                hops = hops,
            ),
        )
        assertFalse(
            shouldSkipNetworkMapAutoload(
                state = ConnState.Connected,
                storedKey = key,
                currentKey = key,
                hops = emptyList(),
            ),
        )
        assertFalse(
            shouldSkipNetworkMapAutoload(
                state = ConnState.Connected,
                storedKey = key,
                currentKey = cacheKey(hideIp = true),
                hops = hops,
            ),
        )
        assertTrue(
            shouldSkipNetworkMapAutoload(
                state = ConnState.Connecting,
                storedKey = key,
                currentKey = cacheKey(sessionUp = false),
                hops = hops,
            ),
        )
        assertFalse(
            shouldSkipNetworkMapAutoload(
                state = ConnState.Ready,
                storedKey = key,
                currentKey = cacheKey(sessionUp = false),
                hops = hops,
            ),
        )
    }

    @Test
    fun syncKeepsFilledProviderWhenReturningToTheTab() {
        val previous = listOf(filledProvider())
        val layout = layout(
            profileHost = "45.129.2.3",
            server = server("45.129.2.3"),
            hideIp = false,
            sessionUp = true,
        )
        val synced = syncNetworkMapHopViews(layout, previous)
        val provider = synced.first { it.hop.kind == NetworkMapHopKind.Provider }
        assertEquals("1.2.3.4", provider.info.ip)
        assertEquals("ISP", provider.info.subtitle)
        assertFalse(provider.loading)
        val vps = synced.first { it.hop.kind == NetworkMapHopKind.Vps }
        assertEquals("45.129.2.3", vps.info.ip)
    }

    @Test
    fun replaceHopUpdatesMatchingKind() {
        val provider = filledProvider()
        val updated = provider.copy(info = IpApiInfo(ip = "8.8.8.8", subtitle = "New"))
        val next = replaceNetworkMapHopView(listOf(provider), updated)
        assertEquals(1, next.size)
        assertEquals("8.8.8.8", next[0].info.ip)
    }

    private fun filledProvider() = NetworkMapHopView(
        hop = NetworkMapHop(NetworkMapHopKind.Provider, NetworkMapCopy.PROVIDER),
        info = IpApiInfo(ip = "1.2.3.4", subtitle = "ISP"),
        loading = false,
    )

    private fun cacheKey(
        sessionUp: Boolean = true,
        hideIp: Boolean = false,
    ) = NetworkMapCacheKey(
        sessionUp = sessionUp,
        profileHost = "45.129.2.3",
        hideIp = hideIp,
        serverId = "s1",
        cascadeEnabled = false,
        cascadeHost = "",
        provisionBase = "http://45.129.2.3:9100",
        deviceId = "dev",
    )
}
