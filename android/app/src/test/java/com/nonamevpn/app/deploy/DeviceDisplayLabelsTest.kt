package com.nonamevpn.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceDisplayLabelsTest {
    @Test
    fun prefersPhoneModelOverDeviceId() {
        val labels = deviceDisplayLabels(
            listOf("dev-abc12345"),
            mapOf("dev-abc12345" to "Pixel 8"),
        )
        assertEquals(listOf("Pixel 8"), labels)
        assertFalse(labels.any { it.contains("dev-") })
    }

    @Test
    fun hidesRawDeviceIdWhenModelUnknown() {
        val one = deviceDisplayLabels(listOf("dev-abc12345"), emptyMap())
        assertEquals(listOf("Телефон"), one)
        val two = deviceDisplayLabels(listOf("dev-aaa", "dev-bbb"), emptyMap())
        assertEquals(listOf("Телефон 1", "Телефон 2"), two)
        assertFalse((one + two).any { it.startsWith("dev-") })
    }
}

class ProvisionUserSummaryParseTest {
    @Test
    fun readsDeviceModelsFromUsersJson() {
        val users = ProvisionAdminApi.parseUsers(
            """
            [{
              "name": "alice",
              "hostId": 2,
              "deviceId": "dev-abc12345",
              "deviceIds": ["dev-abc12345"],
              "maxDevices": 1,
              "hideIp": false,
              "createdAt": "2026-01-01T00:00:00Z",
              "lastExternalIp": "203.0.113.10",
              "online": false,
              "offlineForSec": 90,
              "deviceModels": {"dev-abc12345": "Pixel 8"}
            }]
            """.trimIndent(),
        )
        assertEquals(1, users.size)
        assertEquals("Pixel 8", users[0].deviceModels["dev-abc12345"])
        assertEquals(listOf("Pixel 8"), deviceDisplayLabels(users[0].deviceIds, users[0].deviceModels))
        assertEquals(90L, users[0].offlineForSec)
        assertEquals("203.0.113.10", users[0].lastExternalIp)
        assertFalse(users[0].online)
    }
}
