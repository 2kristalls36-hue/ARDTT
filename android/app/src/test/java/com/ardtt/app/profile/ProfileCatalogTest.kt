package com.ardtt.app.profile

import com.ardtt.app.core.BypassWorkers
import org.junit.Assert.assertEquals
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
        val encoded = VpnProfileJson.encode(sample("alice", "dev-a"))
        assertTrue(encoded.contains(Regex("\"workers\"\\s*:\\s*${BypassWorkers.DEFAULT}")))
        val raw = encoded.replace(
            Regex("\"workers\"\\s*:\\s*${BypassWorkers.DEFAULT}"),
            "\"workers\": 9",
        )
        assertEquals(BypassWorkers.DEFAULT, VpnProfileJson.parse(raw).bypass.workers)
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

    @Test
    fun profileJsonKeepsTrafficQuota() {
        val raw = """
        {
          "name": "alice",
          "deviceId": "dev-a",
          "hostId": 5,
          "expiresAt": 99,
          "trafficLimitBytes": 8000,
          "usedBytes": 1500,
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
        """.trimIndent()
        val parsed = VpnProfileJson.parse(raw)
        assertEquals(8000L, parsed.trafficLimitBytes)
        assertEquals(1500L, parsed.usedBytes)
        val again = VpnProfileJson.parse(VpnProfileJson.encode(parsed))
        assertEquals(8000L, again.trafficLimitBytes)
        assertEquals(1500L, again.usedBytes)
        assertEquals(9100, parsed.provisionPort)
        assertEquals("http://1.2.3.4:9100", parsed.provisionBaseUrl)
        val custom = VpnProfileJson.parse(
            """{"name":"x","hostId":2,"provisionPort":9101,"direct":{"endpoint":"9.9.9.9:51820","privateKey":"p","peerPublicKey":"q","address":"10.8.0.2/32"},"bypass":{"peer":"9.9.9.9:56003","address":"10.9.0.2/32","password":"pw"}}""",
        )
        assertEquals("http://9.9.9.9:9101", custom.provisionBaseUrl)
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
