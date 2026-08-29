package com.nonamevpn.app.core

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
