package com.ardtt.app.ui.profiles

import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.profile.BypassConfig
import com.ardtt.app.profile.DirectConfig
import com.ardtt.app.profile.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCardLogicTest {
    @Test
    fun addressRowStripsPortsAndKeepsOneHost() {
        val hosts = profileCardAddressHosts(
            profile("1.2.3.4:51820", "1.2.3.4:56003"),
            servers = emptyList(),
        )
        assertEquals(listOf("1.2.3.4"), hosts)
    }

    @Test
    fun addressRowMarksCascadeFromSavedServer() {
        val servers = listOf(
            DeployTarget(
                id = "edge",
                name = "Edge",
                host = "10.0.0.1",
                publicHost = "1.2.3.4",
                cascadeEnabled = true,
                cascadeHost = "5.6.7.8:22",
            ),
        )
        assertEquals(
            listOf("1.2.3.4", "5.6.7.8"),
            profileCardAddressHosts(profile("1.2.3.4:51820", "1.2.3.4:56003"), servers),
        )
    }

    @Test
    fun addressRowUsesDistinctDirectAndBypassHosts() {
        assertEquals(
            listOf("1.2.3.4", "9.9.9.9"),
            profileCardAddressHosts(
                profile("1.2.3.4:51820", "9.9.9.9:56003"),
                servers = emptyList(),
            ),
        )
    }

    @Test
    fun remainingTrafficUsesLimitMinusUsed() {
        assertEquals("без лимита", profileTrafficRemainingLabel(0L, 100L))
        assertEquals("осталось 500 Б", profileTrafficRemainingLabel(1000L, 500L))
        assertEquals("осталось 0 Б", profileTrafficRemainingLabel(100L, 250L))
    }

    @Test
    fun liveFactsPreferProvisionSnapshot() {
        val profile = profile("1.2.3.4:51820", "1.2.3.4:56003").copy(
            expiresAt = 10L,
            trafficLimitBytes = 100L,
            usedBytes = 10L,
        )
        val live = profileLiveFactsFromUsers(
            "alice",
            listOf(
                ProvisionAdminApi.UserSummary(
                    name = "alice",
                    hostId = 2,
                    deviceId = "dev-a",
                    hideIp = false,
                    createdAt = "",
                    expiresAt = 99L,
                    trafficLimitBytes = 8_000L,
                    downBytes = 1_000L,
                    upBytes = 500L,
                ),
            ),
        )
        val facts = profileCardFacts(profile, live)
        assertEquals(1_500L, facts.usedBytes)
        assertEquals(8_000L, facts.trafficLimitBytes)
        assertEquals(99L, facts.expiresAt)
        assertNull(profileLiveFactsFromUsers("missing", emptyList()))
    }

    @Test
    fun profileCountSubtitleUsesCallerPlural() {
        assertEquals(
            "1 профиль · активен: Дом",
            profilesCountSubtitle(1, "1 профиль", "Дом", locked = false),
        )
        assertEquals(
            "2 профиля · активен: Дом",
            profilesCountSubtitle(2, "2 профиля", "Дом", locked = false),
        )
        assertEquals(
            "5 профилей · активен: —",
            profilesCountSubtitle(5, "5 профилей", null, locked = false),
        )
        assertEquals(
            "Импортируйте JSON с сервера",
            profilesCountSubtitle(0, "0 профилей", null, locked = false),
        )
    }

    private fun profile(directEndpoint: String, bypassPeer: String) = VpnProfile(
        name = "alice",
        deviceId = "dev-a",
        hostId = 2,
        direct = DirectConfig(
            endpoint = directEndpoint,
            privateKey = "",
            peerPublicKey = "",
            address = "10.8.0.2/32",
            dns = listOf("10.8.0.1"),
            mtu = 1280,
            awg = emptyMap(),
        ),
        bypass = BypassConfig(
            peer = bypassPeer,
            address = "10.9.0.2/32",
            password = "",
            workers = 3,
            transport = "tcp",
            mode = "raw",
            dial = "auto",
        ),
    )
}
