package com.nonamevpn.app.telemetry

import android.content.Context
import com.nonamevpn.app.telemetry.collectors.DeviceInfoCollector
import com.nonamevpn.app.telemetry.collectors.NetworkInfoCollector
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class TelemetryRecorder private constructor(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = TelemetryFileManager(appContext)
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var sessionId: String? = null
    private var writer: BufferedWriter? = null
    private var tempFile: File? = null
    private var startSec: Long = 0
    private var serverIp: String = "unknown"
    private var clientId: String = "unknown"
    private var partIndex = 0
    private val bytesWritten = AtomicLong(0)
    private var writerJob: Job? = null
    private var pollJob: Job? = null
    private val eventChannel = Channel<TelemetryEvent?>(capacity = Channel.BUFFERED)

    fun start(serverIp: String) {
        if (_isRecording.value) return
        this.serverIp = serverIp.ifBlank { "unknown" }
        clientId = TelemetryClientId.get(appContext)
        sessionId = UUID.randomUUID().toString()
        startSec = System.currentTimeMillis() / 1000
        partIndex = 0
        bytesWritten.set(0)
        openWriter()
        _isRecording.value = true

        log(TelemetryEventType.System, JSONObject()
            .put("action", "recording_started")
            .put("client_id", clientId)
            .put("server_ip", this.serverIp))
        log(TelemetryEventType.System, JSONObject().put("device", DeviceInfoCollector.snapshot(appContext)))
        log(TelemetryEventType.System, JSONObject().put("network", NetworkInfoCollector.snapshot(appContext)))

        writerJob = scope.launch { drainEvents() }
        pollJob = scope.launch { periodicSnapshots() }
    }

    fun stop(): TelemetryLogEntry? {
        if (!_isRecording.value) return null
        pollJob?.cancel()
        log(TelemetryEventType.System, JSONObject().put("action", "recording_stopped"))
        _isRecording.value = false
        eventChannel.trySend(null)
        runBlocking {
            withTimeoutOrNull(5_000) {
                writerJob?.join()
            }
        }
        flushAndClose()
        val endSec = System.currentTimeMillis() / 1000
        val temp = tempFile ?: return null
        val final = files.finalizeRecording(temp, clientId, serverIp, startSec, endSec, partIndex)
        sessionId = null
        tempFile = null
        writerJob = null
        return files.listLogs().firstOrNull { it.file.absolutePath == final.absolutePath }
            ?: TelemetryLogEntry(final, final.name, startSec * 1000, (endSec - startSec) * 1000, final.length())
    }

    fun log(type: TelemetryEventType, data: JSONObject) {
        val sid = sessionId ?: return
        val event = TelemetryEvent(
            timestamp = System.currentTimeMillis(),
            eventType = type,
            sessionId = sid,
            data = data,
        )
        eventChannel.trySend(event)
    }

    fun logError(throwable: Throwable, handled: Boolean = false) {
        log(
            TelemetryEventType.Error,
            JSONObject()
                .put("message", throwable.message ?: throwable.javaClass.simpleName)
                .put("type", throwable.javaClass.name)
                .put("handled", handled)
                .put("stacktrace", throwable.stackTraceToString()),
        )
    }

    fun logNavigation(from: String?, to: String) {
        log(
            TelemetryEventType.Navigation,
            JSONObject()
                .put("from", from ?: JSONObject.NULL)
                .put("to", to),
        )
    }

    fun logTouch(screen: String, x: Float, y: Float, elementId: String?) {
        log(
            TelemetryEventType.Touch,
            JSONObject()
                .put("screen", screen)
                .put("x", x.toDouble())
                .put("y", y.toDouble())
                .put("element_id", elementId ?: JSONObject.NULL),
        )
    }

    fun logScroll(screen: String, dx: Float, dy: Float) {
        log(
            TelemetryEventType.Scroll,
            JSONObject()
                .put("screen", screen)
                .put("dx", dx.toDouble())
                .put("dy", dy.toDouble()),
        )
    }

    fun logLifecycle(screen: String, event: String) {
        log(
            TelemetryEventType.Lifecycle,
            JSONObject()
                .put("screen", screen)
                .put("event", event),
        )
    }

    private fun openWriter() {
        val sid = sessionId ?: return
        tempFile = files.tempFile(sid)
        writer = BufferedWriter(FileWriter(tempFile, true))
    }

    private suspend fun drainEvents() {
        var pendingFlush = 0
        while (scope.isActive) {
            val event = eventChannel.receive() ?: break
            writeEvent(event)
            pendingFlush++
            if (pendingFlush >= 40) {
                writer?.flush()
                pendingFlush = 0
            }
            if (bytesWritten.get() >= TelemetryFileManager.MAX_FILE_BYTES) {
                rotateFile()
            }
        }
        writer?.flush()
    }

    private suspend fun periodicSnapshots() {
        while (scope.isActive && _isRecording.value) {
            delay(30_000)
            if (!_isRecording.value) break
            log(TelemetryEventType.System, JSONObject().put("device", DeviceInfoCollector.snapshot(appContext)))
            log(TelemetryEventType.System, JSONObject().put("network", NetworkInfoCollector.snapshot(appContext)))
        }
    }

    private fun writeEvent(event: TelemetryEvent) {
        val line = event.toJsonLine()
        writer?.write(line)
        writer?.newLine()
        bytesWritten.addAndGet(line.length.toLong() + 1)
    }

    private fun rotateFile() {
        flushAndClose()
        val endSec = System.currentTimeMillis() / 1000
        val temp = tempFile
        if (temp != null && temp.exists()) {
            files.finalizeRecording(temp, clientId, serverIp, startSec, endSec, partIndex)
            partIndex++
            startSec = System.currentTimeMillis() / 1000
            bytesWritten.set(0)
            openWriter()
            log(TelemetryEventType.System, JSONObject().put("action", "file_rotated").put("part", partIndex))
        }
    }

    private fun flushAndClose() {
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
    }

    companion object {
        @Volatile
        private var instance: TelemetryRecorder? = null

        fun get(context: Context): TelemetryRecorder {
            return instance ?: synchronized(this) {
                instance ?: TelemetryRecorder(context.applicationContext).also { instance = it }
            }
        }
    }
}
