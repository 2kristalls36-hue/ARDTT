package com.ardtt.app.ui.admin

import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.profile.VpnProfileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientPresentationTest {
    @Test
    fun modeLabelCoversPresenceStates() {
        val now = 1_700_000_000_000L
        assertEquals("Отключён", clientModeLabel(user(deactivated = true), now))
        assertEquals("Истекла", clientModeLabel(user(expiresAt = 1L), now))
        assertEquals("Онлайн", clientModeLabel(user(online = true), now))
        assertEquals("Оффлайн", clientModeLabel(user(online = false, lastSeenAt = 10L), now))
    }

    @Test
    fun offlineDurationUsesRussianUnits() {
        assertEquals("—", formatOfflineDuration(0))
        assertEquals("меньше минуты", formatOfflineDuration(45))
        assertEquals("3 мин", formatOfflineDuration(180))
        assertEquals("2 ч", formatOfflineDuration(2 * 3600))
        assertEquals("1 дн", formatOfflineDuration(86_400))
    }

    @Test
    fun deviceCountLabelIsUsedOverMax() {
        assertEquals("Устройства: 1/1", clientDeviceCountLabel(1, 1))
        assertEquals("Устройства: 0/3", clientDeviceCountLabel(0, 3))
        assertEquals("Устройства: 2/0", clientDeviceCountLabel(2, 0))
    }

    @Test
    fun enableActionFlipsDeactivatedAndLabel() {
        val turnOff = clientEnableAction(deactivated = false)
        assertEquals("Выключить", turnOff.label)
        assertTrue(turnOff.nextDeactivated)

        val turnOn = clientEnableAction(deactivated = true)
        assertEquals("Включить", turnOn.label)
        assertFalse(turnOn.nextDeactivated)
        assertEquals("Выключить", clientEnableActionLabel(deactivated = false))
        assertEquals("Включить", clientEnableActionLabel(deactivated = true))
    }

    @Test
    fun deviceSummaryPrefersPhoneModel() {
        val summary = clientDeviceSummary(
            user(
                deviceIds = listOf("dev-abc"),
                deviceModels = mapOf("dev-abc" to "vivo V2419A"),
            ),
        )
        assertEquals("vivo V2419A", summary)
        assertEquals("—", clientDeviceSummary(user()))
    }

    @Test
    fun deviceSummaryFallsBackToDeviceId() {
        assertEquals(
            "dev-QM3xXdhZ83bnWoFp",
            clientDeviceSummary(user(deviceIds = listOf("dev-QM3xXdhZ83bnWoFp"))),
        )
    }

    @Test
    fun appVersionToneMarksOutdatedBuild() {
        val current = clientAppVersionView(
            user(
                deviceIds = listOf("dev-a"),
                deviceAppVersions = mapOf("dev-a" to "0.5.113-device-id-fallback"),
                deviceAppVersionCodes = mapOf("dev-a" to 131),
            ),
            latestCode = 131,
        )
        assertEquals("0.5.113-device-id-fallback", current.label)
        assertEquals(ClientAppVersionTone.Current, current.tone)

        val outdated = clientAppVersionView(
            user(
                deviceIds = listOf("dev-a"),
                deviceAppVersions = mapOf("dev-a" to "0.5.100"),
                deviceAppVersionCodes = mapOf("dev-a" to 100),
            ),
            latestCode = 131,
        )
        assertEquals("0.5.100", outdated.label)
        assertEquals(ClientAppVersionTone.Outdated, outdated.tone)

        val unknown = clientAppVersionView(user(), latestCode = 131)
        assertEquals("нет версии", unknown.label)
        assertEquals(ClientAppVersionTone.Unknown, unknown.tone)
    }

    @Test
    fun deviceRowShowsThatDeviceVersion() {
        val u = user(
            deviceIds = listOf("dev-a", "dev-b"),
            deviceAppVersions = mapOf(
                "dev-a" to "0.5.199",
                "dev-b" to "0.5.201",
            ),
            deviceAppVersionCodes = mapOf(
                "dev-a" to 217,
                "dev-b" to 219,
            ),
        )
        assertEquals("0.5.199", clientDeviceAppVersion(u, "dev-a"))
        assertEquals("0.5.201", clientDeviceAppVersion(u, "dev-b"))
        assertEquals(null, clientDeviceAppVersion(u, "dev-missing"))
    }

    @Test
    fun expiresToneHighlightsLicenseDate() {
        val now = 1_700_000_000_000L
        assertEquals(ClientExpiresTone.Unlimited, clientExpiresTone(0L, now))
        assertEquals(ClientExpiresTone.Expired, clientExpiresTone(1L, now))
        val soonSec = (now + 3L * 24L * 60L * 60L * 1000L) / 1000L
        assertEquals(ClientExpiresTone.ExpiringSoon, clientExpiresTone(soonSec, now))
        val laterSec = (now + 30L * 24L * 60L * 60L * 1000L) / 1000L
        assertEquals(ClientExpiresTone.Active, clientExpiresTone(laterSec, now))
    }

    @Test
    fun createStubDoesNotBindProfileDevice() {
        val profile = VpnProfileJson.parse(
            """
            {
              "name": "alice",
              "deviceId": "dev-template",
              "hostId": 2,
              "maxDevices": 1,
              "direct": {
                "endpoint": "10.0.0.1:51820",
                "privateKey": "",
                "peerPublicKey": "",
                "address": "10.8.0.2/32",
                "dns": ["10.8.0.1"],
                "mtu": 1280,
                "awg": {}
              },
              "bypass": {
                "peer": "10.0.0.1:56003",
                "address": "10.9.0.2/32",
                "password": "",
                "workers": 9,
                "transport": "tcp",
                "mode": "raw",
                "dial": "auto"
              }
            }
            """.trimIndent(),
        )
        val stub = userStubFromProfile(profile)
        assertEquals("alice", stub.name)
        assertEquals("", stub.deviceId)
        assertTrue(stub.deviceIds.isEmpty())
        assertEquals("—", clientDeviceSummary(stub))
    }

    @Test
    fun addToPhoneKeepsExistingDeviceId() {
        val profile = VpnProfileJson.parse(
            """
            {
              "name": "alice",
              "deviceId": "dev-template",
              "hostId": 2,
              "direct": {
                "endpoint": "10.0.0.1:51820",
                "privateKey": "",
                "peerPublicKey": "",
                "address": "10.8.0.2/32",
                "dns": ["10.8.0.1"],
                "mtu": 1280,
                "awg": {}
              },
              "bypass": {
                "peer": "10.0.0.1:56003",
                "address": "10.9.0.2/32",
                "password": "",
                "workers": 9,
                "transport": "tcp",
                "mode": "raw",
                "dial": "auto"
              }
            }
            """.trimIndent(),
        )
        assertEquals("dev-template", profileWithBindDeviceId(profile, generated = "dev-gen").deviceId)
        assertEquals("dev-gen", profileWithBindDeviceId(profile.copy(deviceId = "  "), generated = "dev-gen").deviceId)
    }

    @Test
    fun addToPhoneBindMessageDependsOnSlot() {
        assertEquals("Добавлен в профили", addToPhoneBindMessage(bound = true))
        assertEquals("Профиль добавлен, устройство не привязалось", addToPhoneBindMessage(bound = false))
        val bound = user(deviceIds = listOf("dev-template"))
        assertTrue(userHasDevice(bound, "dev-template"))
        assertFalse(userHasDevice(bound, "dev-other"))
        assertFalse(userHasDevice(user(), "dev-template"))
    }

    private fun user(
        deactivated: Boolean = false,
        expiresAt: Long = 0L,
        online: Boolean = false,
        lastSeenAt: Long = 0L,
        deviceIds: List<String> = emptyList(),
        deviceModels: Map<String, String> = emptyMap(),
        deviceAppVersions: Map<String, String> = emptyMap(),
        deviceAppVersionCodes: Map<String, Int> = emptyMap(),
    ) = ProvisionAdminApi.UserSummary(
        name = "alice",
        hostId = 2,
        deviceId = deviceIds.firstOrNull().orEmpty(),
        deviceIds = deviceIds,
        maxDevices = 1,
        hideIp = false,
        createdAt = "",
        expiresAt = expiresAt,
        deactivated = deactivated,
        lastSeenAt = lastSeenAt,
        online = online,
        deviceModels = deviceModels,
        deviceAppVersions = deviceAppVersions,
        deviceAppVersionCodes = deviceAppVersionCodes,
    )
}
