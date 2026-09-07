package com.ardtt.app.core

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlayAccessTest {
    @Test
    fun prefersWifiSsidOverCellular() {
        assertEquals(
            "Wi‑Fi · HomeNet",
            formatUnderlayAccessLabel(
                wifiConnected = true,
                wifiSsid = "HomeNet",
                cellularConnected = true,
                operatorName = "T-Mobile",
                generation = "5G",
            ),
        )
    }

    @Test
    fun wifiWithoutSsidIsGeneric() {
        assertEquals(
            "Wi‑Fi",
            formatUnderlayAccessLabel(
                wifiConnected = true,
                wifiSsid = null,
                cellularConnected = false,
                operatorName = null,
                generation = null,
            ),
        )
    }

    @Test
    fun cellularShowsOperatorAndGeneration() {
        assertEquals(
            "T-Mobile · 5G",
            formatUnderlayAccessLabel(
                wifiConnected = false,
                wifiSsid = null,
                cellularConnected = true,
                operatorName = "T-Mobile",
                generation = "5G",
            ),
        )
        assertEquals(
            "Beeline",
            formatUnderlayAccessLabel(
                wifiConnected = false,
                wifiSsid = null,
                cellularConnected = true,
                operatorName = "Beeline",
                generation = null,
            ),
        )
        assertEquals(
            "Мобильная сеть",
            formatUnderlayAccessLabel(
                wifiConnected = false,
                wifiSsid = null,
                cellularConnected = true,
                operatorName = null,
                generation = "4G",
            ),
        )
    }

    @Test
    fun noUnderlay() {
        assertEquals(
            "Нет сети",
            formatUnderlayAccessLabel(
                wifiConnected = false,
                wifiSsid = null,
                cellularConnected = false,
                operatorName = null,
                generation = null,
            ),
        )
    }

    @Test
    fun mapsTelephonyTypesToGeneration() {
        assertEquals("4G", cellularGenerationLabel(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("5G", cellularGenerationLabel(TelephonyManager.NETWORK_TYPE_NR))
        assertNull(cellularGenerationLabel(TelephonyManager.NETWORK_TYPE_UNKNOWN))
    }

    @Test
    fun pickOperatorNameSkipsGenericSimLabels() {
        assertEquals(
            "Beeline",
            pickOperatorName(
                carrierName = "Beeline",
                networkOperatorName = "T-Mobile",
                simOperatorName = "Beeline",
                displayName = "SIM 2",
            ),
        )
        assertEquals(
            "MegaFon",
            pickOperatorName(
                carrierName = null,
                networkOperatorName = "MegaFon",
                displayName = "SIM 1",
            ),
        )
        assertEquals(
            "Work SIM",
            pickOperatorName(
                carrierName = null,
                networkOperatorName = null,
                displayName = "Work SIM",
            ),
        )
        assertNull(
            pickOperatorName(
                carrierName = "  ",
                networkOperatorName = null,
                displayName = "SIM 2",
            ),
        )
    }

    @Test
    fun zombieWifiLosesToValidatedCellular() {
        val zombieWifi = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = false,
            wifiTransport = true,
            cellularTransport = false,
            wifiActuallyConnected = false,
            networkSubId = -1,
            activeDataSubId = 2,
        )
        val liveCell = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = true,
            wifiTransport = false,
            cellularTransport = true,
            wifiActuallyConnected = false,
            networkSubId = 2,
            activeDataSubId = 2,
        )
        assertTrue(liveCell > zombieWifi)
        assertTrue(liveCell > 0)
        assertTrue(zombieWifi <= 0)
    }

    @Test
    fun activeDataSimBeatsOtherCellular() {
        val otherSim = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = true,
            wifiTransport = false,
            cellularTransport = true,
            wifiActuallyConnected = false,
            networkSubId = 1,
            activeDataSubId = 2,
        )
        val dataSim = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = true,
            wifiTransport = false,
            cellularTransport = true,
            wifiActuallyConnected = false,
            networkSubId = 2,
            activeDataSubId = 2,
        )
        assertTrue(dataSim > otherSim)
    }

    @Test
    fun connectedValidatedWifiBeatsCellular() {
        val wifi = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = true,
            wifiTransport = true,
            cellularTransport = false,
            wifiActuallyConnected = true,
            networkSubId = -1,
            activeDataSubId = 2,
        )
        val cell = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = true,
            wifiTransport = false,
            cellularTransport = true,
            wifiActuallyConnected = false,
            networkSubId = 2,
            activeDataSubId = 2,
        )
        assertTrue(wifi > cell)
    }

    @Test
    fun unvalidatedConnectedWifiLosesToValidatedCellular() {
        val captiveWifi = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = false,
            wifiTransport = true,
            cellularTransport = false,
            wifiActuallyConnected = true,
            networkSubId = -1,
            activeDataSubId = 2,
        )
        val liveCell = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = true,
            wifiTransport = false,
            cellularTransport = true,
            wifiActuallyConnected = true,
            networkSubId = 2,
            activeDataSubId = 2,
        )
        assertTrue(liveCell > captiveWifi)
    }

    @Test
    fun validatedWifiWithoutSsidStillBeatsCellular() {
        val wifi = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = true,
            wifiTransport = true,
            cellularTransport = false,
            wifiActuallyConnected = false,
            networkSubId = -1,
            activeDataSubId = 2,
        )
        val cell = scoreUnderlayCandidate(
            hasInternet = true,
            notVpn = true,
            validated = true,
            wifiTransport = false,
            cellularTransport = true,
            wifiActuallyConnected = false,
            networkSubId = 2,
            activeDataSubId = 2,
        )
        assertTrue(wifi > cell)
    }

    @Test
    fun subscriptionIdFromSpecifierUsesReflection() {
        class FakeSpec {
            fun getSubscriptionId(): Int = 42
        }
        assertEquals(42, subscriptionIdFromSpecifier(FakeSpec()))
        assertEquals(-1, subscriptionIdFromSpecifier(null))
        assertEquals(-1, subscriptionIdFromSpecifier("nope"))
    }
}
