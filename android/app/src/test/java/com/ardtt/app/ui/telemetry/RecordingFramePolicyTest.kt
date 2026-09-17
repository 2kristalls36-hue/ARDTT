package com.ardtt.app.ui.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingFramePolicyTest {
    @Test
    fun recordingFrameSitsAboveOtherWindows() {
        assertEquals(RecordingFrameHost.WindowOverlay, recordingFrameHost())
    }
}
