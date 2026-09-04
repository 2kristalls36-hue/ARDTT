package com.ardtt.app.ui

/**
 * Recording must be stopped on the Testing tab before turning testing off.
 * Do not auto-stop telemetry when the user tries to flip that switch.
 */
internal object TestingSessionGuard {
    const val STOP_RECORDING_FIRST =
        "Остановите запись на вкладке «Тест», затем отключите режим."

    fun canLeaveTestingSession(isRecording: Boolean): Boolean = !isRecording

    fun testingTabVisible(
        testingMode: Boolean,
        isRecording: Boolean,
    ): Boolean = testingMode || isRecording
}
