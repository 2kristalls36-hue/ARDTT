package com.nonamevpn.app.ui

/**
 * Recording must be stopped on the Testing tab before leaving tester/admin mode.
 * Do not auto-stop telemetry when the user tries to flip those switches.
 */
internal object TestingSessionGuard {
    const val STOP_RECORDING_FIRST =
        "Остановите запись на вкладке «Тест», затем отключите режим."

    fun canLeaveTestingSession(isRecording: Boolean): Boolean = !isRecording

    fun testingTabVisible(
        admin: Boolean,
        testingMode: Boolean,
        isRecording: Boolean,
    ): Boolean = (admin && testingMode) || isRecording
}
