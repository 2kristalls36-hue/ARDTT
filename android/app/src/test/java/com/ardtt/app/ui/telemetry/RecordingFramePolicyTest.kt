package com.ardtt.app.ui.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFramePolicyTest {
    @Test
    fun recordingFrameSitsAboveOtherWindows() {
        assertEquals(RecordingFrameHost.WindowOverlay, recordingFrameHost())
    }

    @Test
    fun recordingFrameUsesApplicationWindowAboveDialogs() {
        assertEquals(
            RecordingFrameWindowKind.ApplicationWindow,
            recordingFrameWindowKind(),
        )
        assertTrue(recordingFrameRaisesOnAnyFocusChange())
    }
}
