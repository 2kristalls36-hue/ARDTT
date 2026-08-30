package com.nonamevpn.app.deploy

import android.content.Context
import com.jcraft.jsch.Session
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.telemetry.TelemetryBridge
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Admin deploy: SSH → upload stack.tar.gz + install.sh → run Compose on VPS.
 */
class DeployEngine(private val appContext: Context) {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _step = MutableStateFlow("")
    val step: StateFlow<String> = _step.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile private var activeSession: Session? = null
    @Volatile private var activeHost: String = ""

    suspend fun deploy(target: DeployTarget): Result<String> = withContext(Dispatchers.IO) {
        if (_busy.value) return@withContext Result.failure(IllegalStateException("Деплой уже идёт"))
        _busy.value = true
        _progress.value = 0f
        _step.value = "Инициализация…"
        _log.value = emptyList()
        activeHost = target.host.trim()
        var session: Session? = null
        var client: SshClient? = null
        try {
            TelemetryBridge.deploy(
                action = "deploy_started",
                host = activeHost,
                details = JSONObject()
                    .put("target_id", target.id)
                    .put("target_name", target.name)
                    .put("ssh_port", target.sshPort)
                    .put("ssh_user", target.sshUser.trim().ifBlank { "root" })
                    .put("auth_type", if (target.privateKeyPem.isNotBlank()) "key" else "password")
                    .put("is_update", target.lastDeployedAtMs > 0L)
                    .put("public_host", target.publicHost.ifBlank { target.host }.trim()),
            )
            append("Старт деплоя ${target.name.ifBlank { target.host }}")
            emit(0.02f, "Подключение SSH…")
            session = SshClient.connect(
                host = target.host.trim(),
                user = target.sshUser.trim().ifBlank { "root" },
                port = target.sshPort,
                auth = target.auth(),
            )
            activeSession = session
            val ssh = SshClient(session, target.sudoPassword.ifBlank { target.password })
            client = ssh
            append("SSH подключено")
            TelemetryBridge.deploy("ssh_connected", activeHost)

            emit(0.08f, "Подготовка каталога на VPS…")
            ssh.exec("mkdir -p /opt/nonamevpn && chmod 755 /opt/nonamevpn")

            emit(0.12f, "Загрузка stack.tar.gz…")
            val stackBytes = loadStackArchiveBytes()
            ssh.uploadBytes(stackBytes, "/opt/nonamevpn/stack.tar.gz")
            append("Загружен stack.tar.gz (${stackBytes.size / 1024} КБ)")

            emit(0.20f, "Загрузка install.sh…")
            val installBytes = appContext.assets.open("deploy/install.sh").use { it.readBytes() }
            ssh.uploadBytes(installBytes, "/opt/nonamevpn/install.sh")
            ssh.exec("chmod +x /opt/nonamevpn/install.sh")

            val deployVersion = DeployBundle.expectedVersion(appContext)
            runCatching {
                ssh.uploadBytes(
                    (deployVersion + "\n").toByteArray(Charsets.UTF_8),
                    "/opt/nonamevpn/DEPLOY_VERSION",
                )
            }
            append("Версия деплоя $deployVersion")
            TelemetryBridge.deploy(
                "bundle_uploaded",
                activeHost,
                JSONObject()
                    .put("deploy_version", deployVersion)
                    .put("archive_bytes", stackBytes.size),
            )

            val publicHost = target.publicHost.ifBlank { target.host }.trim()
            emit(0.25f, "Запуск установщика…")
            val env = buildString {
                append("NVPN_PUBLIC_HOST="); append(SshClient.shellQuote(publicHost)); append(' ')
                append("NVPN_DIRECT_PORT="); append(target.directPort); append(' ')
                append("NVPN_BYPASS_PORT="); append(target.bypassPort); append(' ')
                append("NVPN_DEPLOY_VERSION="); append(SshClient.shellQuote(deployVersion)); append(' ')
                append("bash /opt/nonamevpn/install.sh")
            }
            var failed: String? = null
            val code = ssh.execStreaming(env, timeoutMs = 45 * 60_000L) { line ->
                append(line)
                when {
                    line.startsWith("NVPN_PROGRESS|") -> {
                        val parts = line.split('|', limit = 3)
                        val frac = parts.getOrNull(1)?.toFloatOrNull() ?: _progress.value
                        val step = parts.getOrNull(2)?.take(160).orEmpty()
                        if (step.isNotBlank()) emit(frac.coerceIn(0f, 1f), step)
                    }
                    line.startsWith("NVPN_ERROR|") -> failed = line.removePrefix("NVPN_ERROR|")
                    line.startsWith("NVPN_DONE|") -> emit(1f, "Готово")
                }
            }
            if (failed != null) {
                TelemetryBridge.deploy(
                    "installer_error",
                    activeHost,
                    JSONObject().put("message", failed),
                )
                error(failed!!)
            }
            if (code != 0) {
                val hint = _log.value.takeLast(8).joinToString(" ")
                val detail = when {
                    code == -1 ->
                        " — SSH-сессия оборвалась во время установки (Wi‑Fi/фон). " +
                            "Повторите деплой: образы собираются до остановки старого стека"
                    hint.contains("no space", ignoreCase = true) ||
                        hint.contains("write /") ||
                        hint.contains("Мало места") ->
                        " — на VPS закончилось место на диске"
                    else -> ""
                }
                TelemetryBridge.deploy(
                    "installer_exit",
                    activeHost,
                    JSONObject()
                        .put("exit_code", code)
                        .put("log_tail", hint),
                )
                error("install.sh exit=$code$detail")
            }
            // Belt-and-suspenders: ensure archive/logs from older installs are gone
            runCatching {
                ssh.exec(
                    "rm -f /opt/nonamevpn/stack.tar.gz /var/log/nvpn-build*.log /var/log/nvpn-install.log; " +
                        "docker builder prune -af >/dev/null 2>&1 || true; " +
                        "docker image prune -f >/dev/null 2>&1 || true",
                )
            }

            val msg = "Стек установлен на $publicHost (/opt/nonamevpn)"
            append(msg)
            emit(1f, msg)
            TelemetryBridge.deploy(
                "deploy_succeeded",
                activeHost,
                JSONObject()
                    .put("message", msg)
                    .put("deploy_version", deployVersion),
            )
            Result.success(msg)
        } catch (t: Throwable) {
            AppLog.e(TAG, "deploy failed: ${t.message ?: t.javaClass.simpleName}")
            TelemetryBridge.handledError("deploy", t)
            val remoteLog = runCatching {
                client?.exec("tail -c 50000 /opt/nonamevpn/install.log 2>/dev/null || true")
            }.getOrNull().orEmpty()
            if (remoteLog.isNotBlank()) {
                TelemetryBridge.deploy(
                    "remote_install_log",
                    activeHost,
                    JSONObject().put("log", remoteLog),
                )
            }
            val msg = t.message?.take(300) ?: t.javaClass.simpleName
            append("Ошибка: $msg")
            emit(_progress.value, "Ошибка")
            TelemetryBridge.deploy(
                "deploy_failed",
                activeHost,
                JSONObject()
                    .put("message", msg)
                    .put("progress", _progress.value.toDouble())
                    .put("step", _step.value)
                    .put("log_tail", _log.value.takeLast(20).joinToString("\n")),
            )
            Result.failure(t)
        } finally {
            runCatching { session?.disconnect() }
            activeSession = null
            _busy.value = false
            activeHost = ""
        }
    }

    fun cancel() {
        runCatching { activeSession?.disconnect() }
        activeSession = null
        _busy.value = false
        append("Отменено")
        TelemetryBridge.deploy("deploy_cancelled", activeHost)
    }

    /**
     * aapt/aapt2 may unpack `*.gz` assets and drop the `.gz` suffix (leaving `stack.tar`).
     * Prefer the opaque `.bin` name; fall back to gz / uncompressed tar (re-gzipped).
     */
    private fun loadStackArchiveBytes(): ByteArray {
        val assets = appContext.assets
        val names = listOf(
            "deploy/stack.tar.gz.bin",
            "deploy/stack.tar.gz",
            "deploy/stack.tar",
        )
        for (name in names) {
            val bytes = runCatching { assets.open(name).use { it.readBytes() } }.getOrNull()
                ?: continue
            if (bytes.isEmpty()) continue
            return if (name.endsWith(".tar") && !name.endsWith(".tar.gz") && !name.endsWith(".tar.gz.bin")) {
                gzipBytes(bytes)
            } else {
                bytes
            }
        }
        error(
            "В APK нет deploy/stack.tar.gz (aapt мог переименовать в stack.tar). " +
                "Выполните scripts/pack-deploy-assets.sh и пересоберите приложение.",
        )
    }

    private fun gzipBytes(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 2)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun emit(fraction: Float, step: String) {
        _progress.value = fraction
        _step.value = step
        TelemetryBridge.deploy(
            "deploy_progress",
            activeHost,
            JSONObject()
                .put("fraction", fraction.toDouble())
                .put("step", step),
        )
    }

    private fun append(line: String) {
        if (line.isBlank()) return
        val next = (_log.value + line).takeLast(400)
        _log.value = next
        TelemetryBridge.deploy(
            "deploy_output",
            activeHost,
            JSONObject().put("line", line),
        )
    }

    companion object {
        private const val TAG = "DeployEngine"
    }
}
