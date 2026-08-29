package com.nonamevpn.app.ui.admin

import com.nonamevpn.app.deploy.ProvisionAdminApi
import org.junit.Assert.assertEquals
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
    fun expiresToneHighlightsLicenseDate() {
        val now = 1_700_000_000_000L
        assertEquals(ClientExpiresTone.Unlimited, clientExpiresTone(0L, now))
        assertEquals(ClientExpiresTone.Expired, clientExpiresTone(1L, now))
        val soonSec = (now + 3L * 24L * 60L * 60L * 1000L) / 1000L
        assertEquals(ClientExpiresTone.ExpiringSoon, clientExpiresTone(soonSec, now))
        val laterSec = (now + 30L * 24L * 60L * 60L * 1000L) / 1000L
        assertEquals(ClientExpiresTone.Active, clientExpiresTone(laterSec, now))
    }

    private fun user(
        deactivated: Boolean = false,
        expiresAt: Long = 0L,
        online: Boolean = false,
        lastSeenAt: Long = 0L,
        deviceIds: List<String> = emptyList(),
        deviceModels: Map<String, String> = emptyMap(),
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
    )
}
