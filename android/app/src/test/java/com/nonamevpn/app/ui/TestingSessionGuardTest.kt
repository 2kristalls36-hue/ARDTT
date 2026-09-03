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
    fun testingTabFollowsModeForAnyUser() {
        assertTrue(
            TestingSessionGuard.testingTabVisible(
                testingMode = true,
                isRecording = false,
            ),
        )
        assertFalse(
            TestingSessionGuard.testingTabVisible(
                testingMode = false,
                isRecording = false,
            ),
        )
    }

    @Test
    fun keepsTestingTabWhileRecordingEvenIfModeOff() {
        assertTrue(
            TestingSessionGuard.testingTabVisible(
                testingMode = false,
                isRecording = true,
            ),
        )
        assertTrue(
            TestingSessionGuard.testingTabVisible(
                testingMode = true,
                isRecording = true,
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
