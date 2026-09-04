package com.ardtt.app.telemetry

import android.content.Context

object TelemetryExceptionHandler {
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        if (previous != null) return
        previous = Thread.getDefaultUncaughtExceptionHandler()
        val recorder = TelemetryRecorder.get(context)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (recorder.isRecording.value) {
                recorder.logError(throwable, handled = false)
                runCatching { recorder.stop() }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
