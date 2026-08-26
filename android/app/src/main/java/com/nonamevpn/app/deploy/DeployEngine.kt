package com.nonamevpn.app.deploy

import android.content.Context
import android.util.Log
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

    suspend fun deploy(target: DeployTarget): Result<String> = withContext(Dispatchers.IO) {
        if (_busy.value) return@withContext Result.failure(IllegalStateException("Деплой уже идёт"))
        _busy.value = true
        _progress.value = 0f
        _step.value = "Инициализация…"
        _log.value = emptyList()
        var session: Session? = null
        try {
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
            append("SSH подключено")

            emit(0.08f, "Подготовка каталога на VPS…")
            ssh.exec("mkdir -p /opt/nonamevpn && chmod 755 /opt/nonamevpn")

            emit(0.12f, "Загрузка stack.tar.gz…")
            val stackBytes = appContext.assets.open("deploy/stack.tar.gz").use { it.readBytes() }
            ssh.uploadBytes(stackBytes, "/opt/nonamevpn/stack.tar.gz")
            append("Загружен stack.tar.gz (${stackBytes.size / 1024} КБ)")

            emit(0.20f, "Загрузка install.sh…")
            val installBytes = appContext.assets.open("deploy/install.sh").use { it.readBytes() }
            ssh.uploadBytes(installBytes, "/opt/nonamevpn/install.sh")
            ssh.exec("chmod +x /opt/nonamevpn/install.sh")

            val publicHost = target.publicHost.ifBlank { target.host }.trim()
            emit(0.25f, "Запуск установщика…")
            val env = buildString {
                append("NVPN_PUBLIC_HOST="); append(SshClient.shellQuote(publicHost)); append(' ')
                append("NVPN_DIRECT_PORT="); append(target.directPort); append(' ')
                append("NVPN_BYPASS_PORT="); append(target.bypassPort); append(' ')
                append("bash /opt/nonamevpn/install.sh")
            }
            var failed: String? = null
            val code = ssh.execStreaming(env, timeoutMs = 45 * 60_000L) { line ->
                append(line)
                when {
                    line.startsWith("NVPN_PROGRESS|") -> {
                        val parts = line.split('|')
                        val frac = parts.getOrNull(1)?.toFloatOrNull() ?: _progress.value
                        val step = parts.getOrNull(2) ?: ""
                        emit(frac.coerceIn(0f, 1f), step)
                    }
                    line.startsWith("NVPN_ERROR|") -> failed = line.removePrefix("NVPN_ERROR|")
                    line.startsWith("NVPN_DONE|") -> emit(1f, "Готово")
                }
            }
            if (failed != null) error(failed!!)
            if (code != 0) error("install.sh exit=$code")

            val msg = "Стек установлен на $publicHost (/opt/nonamevpn)"
            append(msg)
            emit(1f, msg)
            Result.success(msg)
        } catch (t: Throwable) {
            Log.e(TAG, "deploy failed", t)
            val msg = t.message?.take(300) ?: t.javaClass.simpleName
            append("Ошибка: $msg")
            emit(_progress.value, "Ошибка")
            Result.failure(t)
        } finally {
            runCatching { session?.disconnect() }
            activeSession = null
            _busy.value = false
        }
    }

    fun cancel() {
        runCatching { activeSession?.disconnect() }
        activeSession = null
        _busy.value = false
        append("Отменено")
    }

    private fun emit(fraction: Float, step: String) {
        _progress.value = fraction
        _step.value = step
    }

    private fun append(line: String) {
        if (line.isBlank()) return
        val next = (_log.value + line).takeLast(400)
        _log.value = next
    }

    companion object {
        private const val TAG = "DeployEngine"
    }
}
