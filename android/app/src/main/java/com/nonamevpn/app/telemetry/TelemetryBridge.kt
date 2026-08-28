package com.nonamevpn.app.telemetry

import org.json.JSONObject

/**
 * Low-overhead bridge from operational subsystems to the active test recording.
 * Every method is a no-op when recording is disabled.
 */
object TelemetryBridge {
    fun appLog(level: String, tag: String, message: String, verbose: Boolean) {
        recorder()?.log(
            TelemetryEventType.AppLog,
            JSONObject()
                .put("level", level)
                .put("tag", tag.take(80))
                .put("message", TelemetryRedactor.redact(message, 1_024))
                .put("verbose", verbose),
        )
    }

    fun deploy(action: String, host: String, details: JSONObject = JSONObject()) {
        val data = JSONObject()
            .put("action", action)
            .put("host", host.take(255))
        details.keys().forEach { key ->
            val value = details.opt(key)
            data.put(
                key,
                if (value is String) TelemetryRedactor.redact(value) else value,
            )
        }
        recorder()?.log(TelemetryEventType.Deploy, data)
    }

    fun handledError(source: String, throwable: Throwable) {
        recorder()?.log(
            TelemetryEventType.Error,
            JSONObject()
                .put("source", source.take(80))
                .put(
                    "message",
                    TelemetryRedactor.redact(
                        throwable.message ?: throwable.javaClass.simpleName,
                    ),
                )
                .put("type", throwable.javaClass.name)
                .put("handled", true)
                .put(
                    "stacktrace",
                    TelemetryRedactor.redact(throwable.stackTraceToString(), 8_192),
                ),
        )
    }

    fun userError(source: String, message: String) {
        recorder()?.log(
            TelemetryEventType.Error,
            JSONObject()
                .put("source", source.take(80))
                .put("message", TelemetryRedactor.redact(message))
                .put("type", "user_visible")
                .put("handled", true),
        )
    }

    private fun recorder(): TelemetryRecorder? = AppHttpClient.activeRecorderOrNull()
}
