package com.nonamevpn.app.profile

import com.nonamevpn.app.core.BypassWorkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCatalogTest {
    @Test
    fun parseManyAcceptsSingleAndArray() {
        val one = VpnProfileJson.encode(sample("alice", "dev-a"))
        val many = """[${VpnProfileJson.encode(sample("bob", "dev-b"))},${one}]"""
        assertEquals(1, VpnProfileJson.parseMany(one).size)
        assertEquals(listOf("bob", "alice"), VpnProfileJson.parseMany(many).map { it.name })
        val wrapped = """{"profiles":[$one]}"""
        assertEquals("alice", VpnProfileJson.parseMany(wrapped).single().name)
    }

    @Test
    fun parseAlwaysUsesDefaultWorkers() {
        val raw = VpnProfileJson.encode(sample("alice", "dev-a")).replace(
            "\"workers\":${BypassWorkers.DEFAULT}",
            "\"workers\":3",
        )
        assertEquals(BypassWorkers.DEFAULT, VpnProfileJson.parse(raw).bypass.workers)
        assertTrue(VpnProfileJson.encode(sample("alice", "dev-a")).contains("\"workers\":${BypassWorkers.DEFAULT}"))
        assertFalse(VpnProfileJson.encode(sample("alice", "dev-a")).contains("\n"))
    }

    @Test
    fun catalogRoundTripKeepsActiveAndFolder() {
        val profile = sample("home", "dev-home")
        val catalog = ProfileCatalog(
            activeId = "dev-home",
            folders = listOf(DEFAULT_PROFILE_FOLDER, "Работа"),
            items = listOf(
                StoredProfile(id = "dev-home", folder = "Работа", profile = profile),
            ),
        )
        val restored = ProfileCatalogJson.parse(ProfileCatalogJson.encode(catalog))
        assertEquals("dev-home", restored.activeId)
        assertEquals("Работа", restored.items.single().folder)
        assertEquals("home", restored.active?.name)
        assertTrue(restored.folders.contains("Работа"))
    }

    @Test
    fun legacySingleProfileMigrates() {
        val restored = ProfileCatalogJson.fromLegacyProfile(VpnProfileJson.encode(sample("old", "dev-old")))
        assertEquals("dev-old", restored.activeId)
        assertEquals("old", restored.active?.name)
    }

    private fun sample(name: String, deviceId: String) = VpnProfileJson.parse(
        """
        {
          "name": "$name",
          "deviceId": "$deviceId",
          "hostId": 5,
          "prefer": "direct",
          "direct": {
            "endpoint": "1.2.3.4:51820",
            "privateKey": "priv",
            "peerPublicKey": "pub",
            "address": "10.8.0.5/32"
          },
          "bypass": {
            "peer": "1.2.3.4:56003",
            "address": "10.9.0.5/32",
            "password": "pw"
          }
        }
        """.trimIndent(),
    )
}
