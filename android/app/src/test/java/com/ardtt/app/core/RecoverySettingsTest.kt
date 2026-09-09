package com.ardtt.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySettingsTest {
    @Test
    fun localAwgStartWithoutHandshakeIsNotConnected() {
        assertFalse(RecoverySettings.directPathLooksConfirmed(totalRx = 0L, handshakeSec = 0L))
        assertTrue(RecoverySettings.directPathLooksConfirmed(totalRx = 12L, handshakeSec = 0L))
        assertTrue(RecoverySettings.directPathLooksConfirmed(totalRx = 0L, handshakeSec = 3L))
    }
}
