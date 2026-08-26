package com.nonamevpn.app.bypass

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

/**
 * Runs vendored WDTT go_client as [libclient.so] subprocess (Path B: vkcalls + TURN TCP + WRAP/RAW).
 */
class BypassGoProcess(
    private val context: Context,
) {
    private val processRef = AtomicReference<Process?>(null)
    private var logJob: Job? = null
    @Volatile var lastError: String? = null
        private set

    fun binaryPath(): String =
        context.applicationInfo.nativeLibraryDir + "/libclient.so"

    fun binaryExists(): Boolean = File(binaryPath()).isFile

    suspend fun start(
        scope: CoroutineScope,
        args: BypassGoArgs,
        onRawConf: suspend (RawConf) -> Unit,
        onLog: (String) -> Unit = {},
        onFatal: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        stop()
        lastError = null
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
            "-device-id", args.deviceId.ifBlank { "nvpn" },
            "-password", args.password,
            "-vk-auth", "anonymous",
            "-vk-anon-path", anonPath,
            "-turn-tcp",
            "-mode", "rawtun",
            "-tun-fd-sock", TunFdBridge.goSockPath(args.tunSockName),
            "-go-dns", "system",
            "-obfs", "audio",
            "-notls",
        )
        Log.i(TAG, "starting libclient peer=${args.peer} workers=$workers path=$anonPath")

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        val proc = pb.start()
        processRef.set(proc)

        var collectingRaw = false
        val rawBox = StringBuilder()
        var rawDelivered = false

        logJob = scope.launch(Dispatchers.IO) {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (isActive) {
                    val line = reader.readLine() ?: break
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
                    if (fatal != null) {
                        lastError = fatal
                        onFatal(fatal)
                    }
                }
            }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (!rawDelivered) {
                val msg = lastError ?: "go_client завершился (код $code) без RAWCONF — проверьте hash звонка"
                lastError = msg
                onFatal(msg)
            }
        }
    }

    fun stop() {
        logJob?.cancel()
        logJob = null
        val p = processRef.getAndSet(null) ?: return
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
                mtu = fields["MTU"]?.toIntOrNull() ?: 1300,
            )
        }

        fun parseRawConfLine(line: String): RawConf? {
            val body = line.removePrefix("RAWCONF:")
            val parts = body.split("|")
            if (parts.size != 3) return null
            val mtu = parts[2].trim().toIntOrNull() ?: return null
            val ip = parts[0].trim()
            if (ip.isBlank()) return null
            return RawConf(ip, parts[1].trim().ifBlank { "1.1.1.1" }, mtu)
        }

        private fun classifyFatal(line: String): String? {
            val l = line.lowercase()
            return when {
                l.contains("fatal_auth") || l.contains("неверный пароль") ->
                    "Неверный пароль обхода (WRAP)"
                l.contains("хеш мёртв") || l.contains("call not found") || l.contains("callunavailable") ->
                    "Звонок не найден или закрыт — создайте новый hash"
                l.contains("captcha") && (l.contains("required") || l.contains("wait")) ->
                    "VK просит капчу (нужен legacy / WebView)"
                l.contains("all vk credentials failed") ->
                    "Не удалось получить TURN (vkcalls/legacy)"
                else -> null
            }
        }
    }
}
