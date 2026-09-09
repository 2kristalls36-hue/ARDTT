package com.ardtt.app.bypass

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ardtt.app.core.AppLog
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class RawConf(
    val ip: String,
    val dnsCsv: String,
    val mtu: Int,
)

data class BypassGoArgs(
    val peer: String,
    val callHash: String,
    val password: String,
    val deviceId: String,
    val workers: Int,
    val dialPath: DialPath,
    val tunSockName: String,
    val listenPort: Int = 19000,
    val turnTcp: Boolean = true,
)

/**
 * Runs vendored Path B go_client as [libclient.so] subprocess (vkcalls + TURN TCP + WRAP/RAW;
 * qWDTT / SpaceNeuroX lineage).
 */
class BypassGoProcess(
    private val context: Context,
) {
    private val processRef = AtomicReference<Process?>(null)
    private val stopping = AtomicBoolean(false)
    private val parked = AtomicBoolean(false)
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logJob: Job? = null
    private val stdinLock = Any()
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val reqSeq = AtomicLong(1)
    private val sessionGen = AtomicLong(1)
    @Volatile var tunSockName: String? = null
        private set
    private var stdinWriter: BufferedWriter? = null
    @Volatile var lastError: String? = null
        private set

    fun binaryPath(): String =
        context.applicationInfo.nativeLibraryDir + "/libclient.so"

    fun binaryExists(): Boolean = File(binaryPath()).isFile

    suspend fun start(
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        args: BypassGoArgs,
        onRawConf: suspend (RawConf) -> Unit,
        onLog: (String) -> Unit = {},
        onFatal: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        stop()
        stopping.set(false)
        parked.set(false)
        lastError = null
        sessionGen.set(1)
        tunSockName = args.tunSockName
        val bin = binaryPath()
        if (!File(bin).isFile) {
            throw IllegalStateException("libclient.so не найден — соберите scripts/build-bypass-client.sh")
        }

        val anonPath = when (args.dialPath) {
            DialPath.Legacy -> "legacy"
            DialPath.VkCalls, DialPath.Auto -> "vkcalls"
        }
        val workers = args.workers.coerceIn(1, 9)
        val cmd = mutableListOf(
            bin,
            "-peer", args.peer,
            "-vk", args.callHash,
            "-n", workers.toString(),
            "-listen", "127.0.0.1:${args.listenPort}",
            "-device-id", args.deviceId.ifBlank { "ardtt" },
            "-password", args.password,
            "-vk-auth", "anonymous",
            "-vk-anon-path", anonPath,
            "-mode", "rawtun",
            "-tun-fd-sock", TunFdBridge.goSockPath(args.tunSockName),
            "-go-dns", "yandex",
            "-obfs", "audio",
            "-notls",
        )
        cmd.addAll(bypassTurnTransportArgs(args.turnTcp))
        Log.i(TAG, "starting libclient peer=${args.peer} workers=$workers path=$anonPath turnTcp=${args.turnTcp}")

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val stateDir = bypassGoStateDir(context.filesDir).also { it.mkdirs() }
        pb.directory(stateDir)
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        pb.environment()[STATE_DIR_ENV] = stateDir.absolutePath
        AppLog.i(TAG, "state dir=${stateDir.absolutePath}")
        val proc = pb.start()
        processRef.set(proc)
        stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))

        var collectingRaw = false
        val rawBox = StringBuilder()
        var rawDelivered = false

        logJob = logScope.launch {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        completeAckIfNeeded(line)
                        onLog(line)
                        Log.d(TAG, line)

                        if (line.contains("╔") && line.contains("RAW Конфиг")) {
                            collectingRaw = true
                            rawBox.clear()
                            continue
                        }
                        if (collectingRaw) {
                            if (line.contains("╚")) {
                                collectingRaw = false
                                val conf = parseRawBox(rawBox.toString())
                                if (conf != null && !rawDelivered) {
                                    rawDelivered = true
                                    onRawConf(conf)
                                }
                            } else if (line.contains("║")) {
                                rawBox.appendLine(line.replace("║", "").trim())
                            }
                            continue
                        }

                        // Direct RAWCONF line (if ever printed)
                        if (!rawDelivered && line.startsWith("RAWCONF:")) {
                            parseRawConfLine(line)?.let {
                                rawDelivered = true
                                onRawConf(it)
                            }
                        }

                        val fatal = classifyFatal(line)
                        if (fatal != null && !stopping.get() && !parked.get()) {
                            lastError = fatal
                            onFatal(fatal)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!stopping.get() && !parked.get()) {
                    AppLog.w(TAG, "log reader stopped: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                if (parked.get()) {
                    if (!stopping.get()) {
                        lastError = "parked-process-exited"
                        onFatal("parked-process-exited")
                    }
                    return@launch
                }
                if (!rawDelivered && !stopping.get()) {
                    val msg = lastError ?: "Модуль обхода завершился (код $code) без конфигурации. Проверьте код звонка."
                    lastError = msg
                    onFatal(msg)
                }
            }
        }
    }

    /**
     * Stop reading logs without destroying the process (warm VK call park).
     * Must not [Process.waitFor] — the child stays alive until [stop].
     * Prefer keeping the reader alive via [parked] so the stdout pipe cannot fill.
     */
    fun detachLogs() {
        parked.set(true)
    }

    suspend fun sendControl(name: String, vararg args: String): String? {
        val proc = processRef.get() ?: return null
        if (!proc.isAlive) return null
        val reqId = "k${reqSeq.getAndIncrement()}"
        val gen = sessionGen.get()
        val line = buildString {
            append("V1|")
            append(reqId)
            append('|')
            append(gen)
            append('|')
            append(name)
            args.forEach { arg ->
                append('|')
                append(arg)
            }
        }
        val ack = CompletableDeferred<String>()
        pendingAcks[reqId] = ack
        synchronized(stdinLock) {
            val writer = stdinWriter ?: return null
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
        return withTimeoutOrNull(15_000) { ack.await() }
    }

    fun sendControlFireAndForget(name: String, vararg args: String) {
        val proc = processRef.get() ?: return
        if (!proc.isAlive) return
        val reqId = "k${reqSeq.getAndIncrement()}"
        val gen = sessionGen.get()
        val line = buildString {
            append("V1|")
            append(reqId)
            append('|')
            append(gen)
            append('|')
            append(name)
            args.forEach { arg ->
                append('|')
                append(arg)
            }
            append('\n')
        }
        runCatching {
            synchronized(stdinLock) {
                stdinWriter?.write(line)
                stdinWriter?.flush()
            }
        }
    }

    suspend fun attachTun(pfd: ParcelFileDescriptor): Boolean = withContext(Dispatchers.IO) {
        val sock = tunSockName ?: return@withContext false
        val reqId = "k${reqSeq.getAndIncrement()}"
        val gen = sessionGen.get()
        val line = "V1|$reqId|$gen|ATTACH_TUN"
        val ack = CompletableDeferred<String>()
        pendingAcks[reqId] = ack
        synchronized(stdinLock) {
            val writer = stdinWriter ?: return@withContext false
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
        runCatching { TunFdBridge.sendOnce(sock, pfd) }.onFailure {
            AppLog.e(TAG, "ATTACH_TUN fd send: ${it.message}")
            pendingAcks.remove(reqId)
            return@withContext false
        }
        val reply = withTimeoutOrNull(15_000) { ack.await() }
        reply != null && reply.contains("|ok")
    }

    private fun completeAckIfNeeded(line: String) {
        val parsed = parseControlAck(line) ?: return
        pendingAcks.remove(parsed.reqId)?.complete(parsed.line)
    }

    fun stop() {
        parked.set(false)
        stopping.set(true)
        pendingAcks.values.forEach { it.cancel() }
        pendingAcks.clear()
        logJob?.cancel()
        logJob = null
        val p = processRef.getAndSet(null) ?: return
        runCatching { stdinWriter?.close() }
        stdinWriter = null
        tunSockName = null
        runCatching {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
    }

    val isAlive: Boolean
        get() = processRef.get()?.isAlive == true

    companion object {
        private const val TAG = "BypassGo"
        internal const val STATE_DIR_ENV = "ARDTT_STATE_DIR"

        fun parseRawBox(box: String): RawConf? {
            val fields = box.lines().mapNotNull { line ->
                val parts = line.split("=", limit = 2).map { it.trim() }
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            val ip = fields["IP"].orEmpty()
            if (ip.isBlank()) return null
            return RawConf(
                ip = ip,
                dnsCsv = fields["DNS"].orEmpty().ifBlank { "10.9.0.1" },
                mtu = fields["MTU"]?.toIntOrNull() ?: 1280,
            )
        }

        fun parseRawConfLine(line: String): RawConf? {
            val body = line.removePrefix("RAWCONF:")
            val parts = body.split("|")
            if (parts.size < 3) return null
            val mtu = parts[2].trim().toIntOrNull() ?: return null
            val ip = parts[0].trim()
            if (ip.isBlank()) return null
            return RawConf(ip, parts[1].trim().ifBlank { "10.9.0.1" }, mtu)
        }

        internal fun classifyFatal(line: String): String? {
            val kind = classifyBypassFatalKind(line) ?: return null
            return userMessageForBypassFatal(kind)
        }

        data class ControlAck(
            val reqId: String,
            val line: String,
        )

        fun parseControlAck(line: String): ControlAck? {
            if (!line.startsWith("V1|")) return null
            val parts = line.split("|")
            if (parts.size < 6 || parts[3] != "ACK") return null
            return ControlAck(reqId = parts[1], line = line)
        }
    }
}

/** Writable dir for libclient.so (`vk_profile.json`). The APK native dir is read-only. */
internal fun bypassGoStateDir(filesDir: File): File = File(filesDir, "bypass")

internal fun bypassTurnTransportArgs(turnTcp: Boolean): List<String> =
    if (turnTcp) listOf("-turn-tcp") else emptyList()
