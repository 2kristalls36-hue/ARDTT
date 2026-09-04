package com.nonamevpn.app.ui.admin

import com.nonamevpn.app.deploy.ProvisionAdminApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDeployCardLogicTest {
    @Test
    fun publicHostHiddenWhenSameAsSshHost() {
        assertNull(distinctPublicHost("159.194.225.162", "159.194.225.162"))
        assertNull(distinctPublicHost("159.194.225.162", " 159.194.225.162 "))
        assertNull(distinctPublicHost("Example.Host", "example.host"))
        assertNull(distinctPublicHost("10.0.0.1", ""))
        assertNull(distinctPublicHost("10.0.0.1", "   "))
    }

    @Test
    fun publicHostShownOnlyWhenDifferent() {
        assertEquals(
            "203.0.113.10",
            distinctPublicHost("10.0.0.1", "203.0.113.10"),
        )
        assertEquals(
            "vpn.example",
            distinctPublicHost("10.0.0.1", " vpn.example "),
        )
    }

    @Test
    fun cardTitlePrefersNameThenHost() {
        assertEquals("Edge", serverCardTitle("Edge", "10.0.0.1"))
        assertEquals("10.0.0.1", serverCardTitle("  ", "10.0.0.1"))
        assertEquals("10.0.0.1", serverCardTitle("10.0.0.1", "10.0.0.1"))
    }

    @Test
    fun cardMetaOmitsHostWhenTitleIsTheHost() {
        assertEquals(
            "SSH 22",
            serverCardMetaLine("159.194.225.162", "159.194.225.162", 22, "159.194.225.162"),
        )
        assertEquals(
            "10.0.0.1 · SSH 22",
            serverCardMetaLine("Edge", "10.0.0.1", 22, "10.0.0.1"),
        )
        assertEquals(
            "10.0.0.1 · SSH 22 · pub 203.0.113.10",
            serverCardMetaLine("Edge", "10.0.0.1", 22, "203.0.113.10"),
        )
        assertEquals(
            "SSH 2200 · pub 203.0.113.10",
            serverCardMetaLine("10.0.0.1", "10.0.0.1", 2200, "203.0.113.10"),
        )
    }

    @Test
    fun osBadgeVersionStripsDuplicatedName() {
        assertNull(serverOsBadgeVersionText("ubuntu", ""))
        assertNull(serverOsBadgeVersionText("ubuntu", "Ubuntu"))
        assertEquals("24.04.1 LTS", serverOsBadgeVersionText("ubuntu", "Ubuntu 24.04.1 LTS"))
        assertEquals("40", serverOsBadgeVersionText("fedora", "Fedora 40"))
        assertEquals("Pop!_OS 22.04 LTS", serverOsBadgeVersionText("ubuntu", "Pop!_OS 22.04 LTS"))
        assertEquals("12", serverOsBadgeVersionText("debian", "12"))
    }

    @Test
    fun updateButtonHiddenWhenOnlineAndCurrent() {
        val health = HealthUi.Online("1.0.6")
        assertFalse(isDeployOutdated(health, "1.0.6"))
        assertFalse(shouldShowUpdateDeployButton(health, "1.0.6"))
        assertFalse(shouldShowUpdateDeployButton(health, " 1.0.6 "))
    }

    @Test
    fun updateButtonShownWhenOutdatedOfflineOrUnknown() {
        assertTrue(shouldShowUpdateDeployButton(HealthUi.Online("1.0.5"), "1.0.6"))
        assertTrue(shouldShowUpdateDeployButton(HealthUi.Online(""), "1.0.6"))
        assertTrue(shouldShowUpdateDeployButton(HealthUi.Offline, "1.0.6"))
        assertTrue(shouldShowUpdateDeployButton(HealthUi.Checking, "1.0.6"))
        assertTrue(shouldShowUpdateDeployButton(null, "1.0.6"))
    }

    @Test
    fun freshnessChipOnlyWhenOnlineAndOutdated() {
        assertNull(deployFreshnessChipText(HealthUi.Online("1.0.6"), "1.0.6"))
        assertEquals(
            "Требуется обновление · 1.0.5 → 1.0.6",
            deployFreshnessChipText(HealthUi.Online("1.0.5"), "1.0.6"),
        )
        assertNull(deployFreshnessChipText(HealthUi.Offline, "1.0.6"))
        assertNull(deployFreshnessChipText(HealthUi.Checking, "1.0.6"))
        assertNull(deployFreshnessChipText(null, "1.0.6"))
    }

    @Test
    fun statusLineDoesNotRepeatFreshnessWords() {
        val current = healthStatusLabel(HealthUi.Online("1.0.6"), lastDeployedAtMs = 0L)
        assertEquals("● Онлайн · деплой 1.0.6", current)
        assertFalse(current.contains("актуален"))
        assertFalse(current.contains("нужно обновить"))

        val outdated = healthStatusLabel(HealthUi.Online("1.0.5"), lastDeployedAtMs = 0L)
        assertEquals("● Онлайн · деплой 1.0.5", outdated)
        assertFalse(outdated.contains("актуален"))
        assertFalse(outdated.contains("нужно обновить"))
    }

    @Test
    fun statusLineShowsPingInsteadOfDeployAge() {
        val withPing = healthStatusLabel(
            HealthUi.Online("1.0.12", pingMs = 42L),
            lastDeployedAtMs = 1_700_000_000_000L,
        )
        assertEquals("● Онлайн · деплой 1.0.12 · 42 мс", withPing)
        assertFalse(withPing.contains("назад"))
        assertFalse(withPing.contains("мин"))
    }

    @Test
    fun pingFormatterOmitsNonPositive() {
        assertEquals("", formatHealthPingMs(-1L))
        assertEquals("", formatHealthPingMs(0L))
        assertEquals("1 мс", formatHealthPingMs(1L))
    }

    @Test
    fun healthUiKeepsPingFromProbe() {
        val online = healthUiOf(
            ProvisionAdminApi.HealthInfo(ok = true, deployVersion = "1.0.12", pingMs = 18L),
        )
        assertEquals(HealthUi.Online("1.0.12", 18L), online)
        assertEquals(HealthUi.Offline, healthUiOf(null))
        assertEquals(
            HealthUi.Offline,
            healthUiOf(ProvisionAdminApi.HealthInfo(ok = false, pingMs = 9L)),
        )
    }
}
