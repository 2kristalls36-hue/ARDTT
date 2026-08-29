package com.nonamevpn.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestingSessionGuardTest {
    @Test
    fun refusesLeaveWhileRecording() {
        assertFalse(TestingSessionGuard.canLeaveTestingSession(isRecording = true))
        assertTrue(TestingSessionGuard.canLeaveTestingSession(isRecording = false))
    }

    @Test
    fun keepsTestingTabWhileRecordingEvenIfModesOff() {
        assertTrue(
            TestingSessionGuard.testingTabVisible(
                admin = false,
                testingMode = false,
                isRecording = true,
            ),
        )
        assertFalse(
            TestingSessionGuard.testingTabVisible(
                admin = false,
                testingMode = false,
                isRecording = false,
            ),
        )
        assertTrue(
            TestingSessionGuard.testingTabVisible(
                admin = true,
                testingMode = true,
                isRecording = false,
            ),
        )
    }

    @Test
    fun stopRecordingCopyAsksToUseTestingTab() {
        val copy = TestingSessionGuard.STOP_RECORDING_FIRST
        assertTrue(copy.contains("Остановите запись"))
        assertTrue(copy.contains("Тест"))
        assertEquals(
            "Остановите запись на вкладке «Тест», затем отключите режим.",
            copy,
        )
    }
}
