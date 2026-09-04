package com.ardtt.app.telemetry

import android.content.Context
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject

object AppHttpClient {
    private val telemetryInterceptor = TelemetryOkHttpInterceptor()
    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .addInterceptor(telemetryInterceptor)

    fun default(): OkHttpClient = builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    internal fun activeRecorderOrNull(): TelemetryRecorder? {
        return appContext
            ?.let(TelemetryRecorder::get)
            ?.takeIf { it.isRecording.value }
    }
}

private class TelemetryOkHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val recorder = AppHttpClient.activeRecorderOrNull()
            ?: return chain.proceed(chain.request())
        val request = chain.request()
        val start = System.currentTimeMillis()

        val reqHeaders = JSONObject()
        for (name in request.headers.names()) {
            val value = request.header(name).orEmpty()
            reqHeaders.put(
                name,
                if (isSensitiveHeader(name)) "[REDACTED]"
                else TelemetryRedactor.redact(value),
            )
        }
        val reqBodyPreview = request.body?.let { body ->
            runCatching {
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                TelemetryRedactor.redact(buffer.readUtf8(), MAX_BODY_BYTES)
            }.getOrNull()
        }

        recorder.log(
            TelemetryEventType.Network,
            JSONObject()
                .put("phase", "request")
                .put("url", TelemetryRedactor.redact(request.url.toString()))
                .put("method", request.method)
                .put("headers", reqHeaders)
                .put("body", reqBodyPreview ?: JSONObject.NULL),
        )

        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            recorder.log(
                TelemetryEventType.Network,
                JSONObject()
                    .put("phase", "error")
                    .put("url", TelemetryRedactor.redact(request.url.toString()))
                    .put("method", request.method)
                    .put("message", e.message ?: e.javaClass.simpleName)
                    .put("elapsed_ms", System.currentTimeMillis() - start),
            )
            throw e
        }

        val elapsed = System.currentTimeMillis() - start
        val bodyString = runCatching {
            response.peekBody(MAX_BODY_BYTES.toLong()).string()
        }.getOrElse { "" }

        recorder.log(
            TelemetryEventType.Network,
            JSONObject()
                .put("phase", "response")
                .put("url", TelemetryRedactor.redact(request.url.toString()))
                .put("method", request.method)
                .put("status", response.code)
                .put("elapsed_ms", elapsed)
                .put("body", TelemetryRedactor.redact(bodyString, MAX_BODY_BYTES)),
        )

        return response
    }

    private fun isSensitiveHeader(name: String): Boolean {
        return name.equals("Authorization", ignoreCase = true) ||
            name.equals("Cookie", ignoreCase = true) ||
            name.equals("Set-Cookie", ignoreCase = true) ||
            name.startsWith("X-Auth-", ignoreCase = true)
    }

    companion object {
        private const val MAX_BODY_BYTES = 16 * 1024
    }
}
