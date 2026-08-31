package com.nonamevpn.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingUiActionDeployTest {
    @Test
    fun openDeployIsConsumedOnce() {
        PendingUiAction.consumeOpenDeploy()
        PendingUiAction.requestOpenDeploy("  srv-1  ")
        assertEquals("srv-1", PendingUiAction.openDeployServerId.value)
        assertEquals("srv-1", PendingUiAction.consumeOpenDeploy())
        assertNull(PendingUiAction.consumeOpenDeploy())
        assertNull(PendingUiAction.openDeployServerId.value)
    }
}
