package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabRadioPlanTest {
    @Test
    fun wifiWithoutLabModeIsNotReady() {
        val plan = planLabRadios(
            wifiUp = true,
            cellularUp = true,
            labCellularOnly = false,
        )
        assertFalse(plan.ready)
        assertEquals(LabControlLink.Wifi, plan.control)
        assertEquals(UnderlayKind.Wifi, plan.testUnderlay)
        assertTrue(plan.reason.contains("Direct") || plan.reason.contains("AP"))
    }

    @Test
    fun wifiPlusLabModeUsesCellularUnderlay() {
        val plan = planLabRadios(
            wifiUp = true,
            cellularUp = true,
            labCellularOnly = true,
        )
        assertTrue(plan.ready)
        assertEquals(LabControlLink.Wifi, plan.control)
        assertEquals(UnderlayKind.Cellular, plan.testUnderlay)
        assertFalse(autoUsesDirectOnWifi(ConnPathMode.Auto, plan.testUnderlay))
    }

    @Test
    fun cellularOnlyCanUseVpsAsControl() {
        val plan = planLabRadios(
            wifiUp = false,
            cellularUp = true,
            labCellularOnly = true,
            vpsTcpOnCellular = true,
        )
        assertTrue(plan.ready)
        assertEquals(LabControlLink.CellularToVps, plan.control)
        assertEquals(UnderlayKind.Cellular, plan.testUnderlay)
    }

    @Test
    fun noCellularCannotTestWhitelist() {
        val plan = planLabRadios(
            wifiUp = true,
            cellularUp = false,
            labCellularOnly = true,
        )
        assertFalse(plan.ready)
        assertTrue(plan.reason.contains("LTE"))
    }

    @Test
    fun noControlLinkIsNotReady() {
        val plan = planLabRadios(
            wifiUp = false,
            cellularUp = true,
            labCellularOnly = true,
            vpsTcpOnCellular = false,
        )
        assertFalse(plan.ready)
        assertEquals(LabControlLink.None, plan.control)
    }

    @Test
    fun labEffectiveUnderlayIgnoresWifiWhenCellularExists() {
        assertEquals(
            UnderlayKind.Cellular,
            labEffectiveUnderlayKind(
                labCellularOnly = true,
                raw = UnderlayKind.Wifi,
                cellularAvailable = true,
            ),
        )
        assertEquals(
            UnderlayKind.Wifi,
            labEffectiveUnderlayKind(
                labCellularOnly = false,
                raw = UnderlayKind.Wifi,
                cellularAvailable = true,
            ),
        )
    }
}
