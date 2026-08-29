package com.nonamevpn.app.ui.admin

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
}
