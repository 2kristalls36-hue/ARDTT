package com.nonamevpn.app.telemetry

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

object AppHttpClient {
    private val telemetryInterceptor = TelemetryOkHttpInterceptor()

    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .addInterceptor(telemetryInterceptor)

    fun default(): OkHttpClient = builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

private class TelemetryOkHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val recorder = recorderOrNull() ?: return chain.proceed(chain.request())
        val request = chain.request()
        val start = System.currentTimeMillis()

        val reqHeaders = JSONObject()
        for (name in request.headers.names()) {
            reqHeaders.put(name, request.header(name))
        }
        val reqBodyPreview = request.body?.let { body ->
            runCatching {
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                truncate(buffer.readUtf8())
            }.getOrNull()
        }

        recorder.log(
            TelemetryEventType.Network,
            JSONObject()
                .put("phase", "request")
                .put("url", request.url.toString())
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
                    .put("url", request.url.toString())
                    .put("method", request.method)
                    .put("message", e.message ?: e.javaClass.simpleName)
                    .put("elapsed_ms", System.currentTimeMillis() - start),
            )
            throw e
        }

        val elapsed = System.currentTimeMillis() - start
        val bodyString = runCatching {
            response.peekBody(MAX_BODY_BYTES).string()
        }.getOrElse { "" }

        recorder.log(
            TelemetryEventType.Network,
            JSONObject()
                .put("phase", "response")
                .put("url", request.url.toString())
                .put("method", request.method)
                .put("status", response.code)
                .put("elapsed_ms", elapsed)
                .put("body", truncate(bodyString)),
        )

        return response
    }

    private fun recorderOrNull(): TelemetryRecorder? {
        return runCatching { TelemetryRecorder.get(appContextHolder) }.getOrNull()
            ?.takeIf { it.isRecording.value }
    }

    private fun truncate(raw: String): String {
        if (raw.length <= MAX_BODY_BYTES) return raw
        return raw.take(MAX_BODY_BYTES) + "…[truncated ${raw.length} bytes]"
    }

    companion object {
        private const val MAX_BODY_BYTES = 16 * 1024
        @Volatile
        var appContextHolder: android.content.Context = throw IllegalStateException("AppHttpClient not initialized")
    }
}
