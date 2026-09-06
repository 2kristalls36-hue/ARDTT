package com.ardtt.app.deploy

import android.content.Context
import com.jcraft.jsch.Session
import com.ardtt.app.core.AppLog
import com.ardtt.app.telemetry.TelemetryBridge
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Admin deploy: phone downloads server/ from GitHub, SSH-uploads it with
 * install.sh, runs Compose on the VPS; or SSH uninstall then drops the card.
 * Protocol and VPS layout: docs/DEPLOY.md. Canonical installer: server/install.sh.
 */
class DeployEngine(private val appContext: Context) {
    private val running = AtomicBoolean(false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _step = MutableStateFlow("")
    val step: StateFlow<String> = _step.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _isUpdate = MutableStateFlow(false)
    val isUpdate: StateFlow<Boolean> = _isUpdate.asStateFlow()

    private val _isUninstall = MutableStateFlow(false)
    val isUninstall: StateFlow<Boolean> = _isUninstall.asStateFlow()

    private val _outcome = MutableStateFlow<String?>(null)
    val outcome: StateFlow<String?> = _outcome.asStateFlow()

    private val _activeTargetId = MutableStateFlow<String?>(null)
    val activeTargetId: StateFlow<String?> = _activeTargetId.asStateFlow()

    private val _hopTrack = MutableStateFlow(DeployHopTrack())
    val hopTrack: StateFlow<DeployHopTrack> = _hopTrack.asStateFlow()

    @Volatile private var activeSession: Session? = null
    @Volatile private var activeHost: String = ""
    @Volatile private var pendingTarget: DeployTarget? = null

    val activeHostValue: String get() = activeHost

    fun pendingHostLabel(): String {
        val t = pendingTarget ?: return activeHost
        return t.name.ifBlank { t.host }.ifBlank { activeHost }
    }

    /**
     * Start (or update) deploy in a foreground service. Returns false if another
     * deploy is already running.
     */
    fun enqueue(target: DeployTarget, isUpdate: Boolean): Boolean =
        enqueue(target, if (isUpdate) DeployJobKind.Update else DeployJobKind.Install)

    fun enqueueUninstall(target: DeployTarget): Boolean =
        enqueue(target, DeployJobKind.Uninstall)

    fun enqueue(target: DeployTarget, kind: DeployJobKind): Boolean {
        if (!running.compareAndSet(false, true)) return false
        pendingTarget = target
        _isUpdate.value = kind == DeployJobKind.Update
        _isUninstall.value = kind == DeployJobKind.Uninstall
        _activeTargetId.value = target.id
        _hopTrack.value = DeployHopTrack.from(target)
        _busy.value = true
        _progress.value = 0f
        _step.value = "Инициализация…"
        _log.value = emptyList()
        _outcome.value = null
        activeHost = target.host.trim()
        TelemetryBridge.deploy(
            "deploy_enqueued",
            activeHost,
            JSONObject()
                .put("target_id", target.id)
                .put("is_update", kind == DeployJobKind.Update)
                .put("is_uninstall", kind == DeployJobKind.Uninstall)
                .put("job_kind", kind.name.lowercase())
                .put("target_name", target.name),
        )
        return try {
            DeployService.start(appContext)
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "enqueue failed: ${t.message ?: t.javaClass.simpleName}")
            running.set(false)
            _busy.value = false
            pendingTarget = null
            _activeTargetId.value = null
            _outcome.value = "Ошибка: не удалось запустить фоновую задачу"
            false
        }
    }

    /** Called from [DeployService] on a background dispatcher. */
    suspend fun runFromService(): Result<String> {
        val target = pendingTarget
            ?: return Result.failure(IllegalStateException("Нет задания деплоя"))
        val uninstall = _isUninstall.value
        return try {
            if (uninstall) executeUninstall(target) else execute(target)
        } finally {
            running.set(false)
            _busy.value = false
            pendingTarget = null
            // Keep isUpdate / isUninstall / activeTargetId until the finished notification is built.
        }
    }

    private suspend fun execute(target: DeployTarget): Result<String> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var client: SshClient? = null
        activeHost = target.host.trim()
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
                    .put("is_update", _isUpdate.value)
                    .put("public_host", target.publicHost.ifBlank { target.host }.trim())
                    .put("cascade_enabled", target.cascadeEnabled),
            )
            append("Старт деплоя ${target.name.ifBlank { target.host }}")
            val deployVersion = DeployBundle.expectedVersion(appContext)
            emit(0.02f, "Загрузка стека $deployVersion из GitHub…")
            val payload = DeployStackFetcher(appContext, deployVersion).fetch { frac ->
                emit(0.02f + frac * 0.06f, "Загрузка стека из GitHub…")
            }
            append("Стек $deployVersion ← ${payload.sourceLabel} (${payload.stackBytes.size / 1024} КБ)")
            TelemetryBridge.deploy(
                "bundle_downloaded",
                activeHost,
                JSONObject()
                    .put("deploy_version", deployVersion)
                    .put("source", payload.sourceLabel)
                    .put("git_ref", payload.gitRef)
                    .put("archive_bytes", payload.stackBytes.size),
            )
            val stackBytes = payload.stackBytes
            val installBytes = payload.installBytes
            val gitRepo = DeployStackSource.gitRepoHttps()
            val gitRef = payload.gitRef
            val publicHost = target.publicHost.ifBlank { target.host }.trim()
            val entryHost = target.host.trim()
            val verb = if (_isUpdate.value) "обновление" else "установка"

            var exitPub = ""
            var exitCascadeListen = DeployInstallEnv.CASCADE_LISTEN_PORT
            var resolvedDirect = target.directPort
            var resolvedBypass = target.bypassPort
            if (target.cascadeEnabled) {
                val exitHost = target.cascadeHost.trim()
                if (exitHost.isBlank()) error("Не указан host второго сервера")
                emitOn(exitHost, 0.08f, "Подключение SSH, $verb выходного стека…")
                append("VPS 2 (выход): $exitHost")
                val exitSession = SshClient.connect(
                    host = exitHost,
                    user = target.cascadeSshUser(),
                    port = target.cascadePort,
                    auth = target.cascadeAuth(),
                )
                activeSession = exitSession
                val exitSsh = SshClient(exitSession, target.cascadePassword)
                client = exitSsh
                try {
                    val installed = uploadAndInstall(
                        ssh = exitSsh,
                        hostLabel = exitHost,
                        publicHost = exitHost,
                        stackBytes = stackBytes,
                        installBytes = installBytes,
                        deployVersion = deployVersion,
                        command = DeployInstallEnv.command(
                            publicHost = exitHost,
                            directPort = target.directPort,
                            bypassPort = target.bypassPort,
                            deployVersion = deployVersion,
                            role = "exit",
                            cascadeEnabled = true,
                            autoPorts = target.autoPorts,
                            gitRepo = gitRepo,
                            gitRef = gitRef,
                        ),
                        progressStart = 0.10f,
                        progressEnd = 0.48f,
                    )
                    exitPub = installed.cascadePublicKey
                    if (exitPub.isBlank()) {
                        error("Выходной VPS не отдал ключ каскада (ARDTT_CASCADE_PUBLIC_KEY)")
                    }
                    installed.cascadeListenPort?.let { exitCascadeListen = it }
                        ?: installed.directPort?.let { exitCascadeListen = it }
                    append("Ключ выхода получен с $exitHost")
                    markTrackedHostDone(exitHost)
                } finally {
                    runCatching { exitSession.disconnect() }
                    if (activeSession === exitSession) activeSession = null
                }
            }

            emitOn(entryHost, if (target.cascadeEnabled) 0.50f else 0.08f, "Подключение SSH, $verb входного стека…")
            session = SshClient.connect(
                host = entryHost,
                user = target.sshUser.trim().ifBlank { "root" },
                port = target.sshPort,
                auth = target.auth(),
            )
            activeSession = session
            val ssh = SshClient(session, target.sudoPassword.ifBlank { target.password })
            client = ssh
            append("SSH подключено ($entryHost)")
            TelemetryBridge.deploy("ssh_connected", activeHost)

            val entryCmd = DeployInstallEnv.command(
                publicHost = publicHost,
                directPort = target.directPort,
                bypassPort = target.bypassPort,
                deployVersion = deployVersion,
                role = "entry",
                cascadeEnabled = target.cascadeEnabled,
                cascadePeerEndpoint = if (target.cascadeEnabled) {
                    DeployInstallEnv.peerEndpoint(target.cascadeHost, exitCascadeListen)
                } else {
                    ""
                },
                cascadePeerPublicKey = exitPub,
                autoPorts = target.autoPorts,
                gitRepo = gitRepo,
                gitRef = gitRef,
            )
            val entryInstalled = uploadAndInstall(
                ssh = ssh,
                hostLabel = entryHost,
                publicHost = publicHost,
                stackBytes = stackBytes,
                installBytes = installBytes,
                deployVersion = deployVersion,
                command = entryCmd,
                progressStart = if (target.cascadeEnabled) 0.50f else 0.08f,
                progressEnd = if (target.cascadeEnabled) 0.92f else 0.96f,
            )
            val entryPub = entryInstalled.cascadePublicKey
            entryInstalled.directPort?.let { resolvedDirect = it }
            entryInstalled.bypassPort?.let { resolvedBypass = it }
            markTrackedHostDone(entryHost)

            if (target.cascadeEnabled) {
                val exitHost = target.cascadeHost.trim()
                emitOn(exitHost, 0.94f, "Запись ключа входа и перезапуск контейнера…")
                val exitSession = SshClient.connect(
                    host = exitHost,
                    user = target.cascadeSshUser(),
                    port = target.cascadePort,
                    auth = target.cascadeAuth(),
                )
                activeSession = exitSession
                val exitSsh = SshClient(exitSession, target.cascadePassword)
                try {
                    if (entryPub.isBlank()) {
                        error("Входной VPS не отдал ключ каскада")
                    }
                    exitSsh.exec(
                        "install -d -m 700 /opt/ardtt/stack/data && " +
                            "printf '%s\\n' ${SshClient.shellQuote(entryPub)} " +
                            "> /opt/ardtt/stack/data/cascade.peer.pub && " +
                            "chmod 644 /opt/ardtt/stack/data/cascade.peer.pub && " +
                            "(docker restart ardtt >/dev/null 2>&1 || docker restart ardtt-host >/dev/null 2>&1 || docker restart ardtt-cascade >/dev/null 2>&1 || docker restart nvpn-cascade >/dev/null 2>&1 || true)",
                    )
                    append("Пир входа записан на $exitHost")
                } finally {
                    runCatching { exitSession.disconnect() }
                    activeSession = session
                }
            }

            runCatching {
                ssh.exec(
                    "rm -f /opt/ardtt/stack.tar.gz /var/log/ardtt-build*.log /var/log/ardtt-install.log; " +
                        "docker builder prune -af >/dev/null 2>&1 || true; " +
                        "docker image prune -f >/dev/null 2>&1 || true",
                )
            }

            val msg = if (target.cascadeEnabled) {
                "Каскад установлен: вход $publicHost → выход ${target.cascadeHost.trim()}"
            } else {
                "Стек установлен на $publicHost (/opt/ardtt)"
            }
            append(msg)
            finishHopsSuccess()
            emit(1f, msg)
            val deployedAt = System.currentTimeMillis()
            val osInfo = runCatching {
                ServerOsProbe.parse(ssh.exec("cat /etc/os-release", timeoutMs = 12_000L))
            }.getOrNull()
            runCatching {
                val repo = ServersRepository.get(appContext)
                val stored = repo.snapshot().find { it.id == target.id } ?: target
                repo.upsert(
                    stored.copy(
                        lastDeployedAtMs = deployedAt,
                        directPort = resolvedDirect,
                        bypassPort = resolvedBypass,
                        osId = osInfo?.osId?.trim()?.ifBlank { stored.osId } ?: stored.osId,
                        osVersion = osInfo?.osVersionLabel?.trim()?.ifBlank { stored.osVersion }
                            ?: stored.osVersion,
                    ),
                )
            }
            TelemetryBridge.deploy(
                "deploy_succeeded",
                activeHost,
                JSONObject()
                    .put("message", msg)
                    .put("deploy_version", deployVersion),
            )
            _outcome.value = msg
            Result.success(msg)
        } catch (t: Throwable) {
            AppLog.e(TAG, "deploy failed: ${t.message ?: t.javaClass.simpleName}")
            TelemetryBridge.handledError("deploy", t)
            val remoteLog = runCatching {
                client?.exec("tail -c 50000 /opt/ardtt/install.log 2>/dev/null || true")
            }.getOrNull().orEmpty()
            if (remoteLog.isNotBlank()) {
                TelemetryBridge.deploy(
                    "remote_install_log",
                    activeHost,
                    JSONObject().put("log", remoteLog),
                )
            }
            val cancelled = _log.value.any { it.contains("Отменено") }
            val msg = if (cancelled) {
                "Отменено"
            } else {
                t.message?.take(300) ?: t.javaClass.simpleName
            }
            append("Ошибка: $msg")
            emit(_progress.value, if (cancelled) "Отменено" else "Ошибка")
            TelemetryBridge.deploy(
                "deploy_failed",
                activeHost,
                JSONObject()
                    .put("message", msg)
                    .put("progress", _progress.value.toDouble())
                    .put("step", _step.value)
                    .put("log_tail", _log.value.takeLast(20).joinToString("\n")),
            )
            val shown = if (cancelled) "Отменено" else "Ошибка: $msg"
            _outcome.value = shown
            Result.failure(t)
        } finally {
            runCatching { session?.disconnect() }
            activeSession = null
            activeHost = ""
        }
    }

    private suspend fun executeUninstall(target: DeployTarget): Result<String> = withContext(Dispatchers.IO) {
        var session: Session? = null
        try {
            TelemetryBridge.deploy(
                action = "uninstall_started",
                host = activeHost,
                details = JSONObject()
                    .put("target_id", target.id)
                    .put("target_name", target.name)
                    .put("ssh_port", target.sshPort)
                    .put("ssh_user", target.sshUser.trim().ifBlank { "root" })
                    .put("auth_type", if (target.privateKeyPem.isNotBlank()) "key" else "password")
                    .put("cascade_enabled", target.cascadeEnabled),
            )
            append("Старт удаления ${target.name.ifBlank { target.host }}")

            if (target.cascadeEnabled) {
                val exitHost = target.cascadeHost.trim()
                if (exitHost.isBlank()) error("Не указан host второго сервера")
                emitOn(exitHost, 0.04f, "Подключение SSH, снятие выходного стека…")
                append("VPS 2 (выход): $exitHost")
                val exitSession = SshClient.connect(
                    host = exitHost,
                    user = target.cascadeSshUser(),
                    port = target.cascadePort,
                    auth = target.cascadeAuth(),
                )
                activeSession = exitSession
                try {
                    val exitSsh = SshClient(exitSession, target.cascadePassword)
                    wipeRemoteStack(exitSsh, exitHost, 0.06f, 0.48f)
                    markTrackedHostDone(exitHost)
                } finally {
                    runCatching { exitSession.disconnect() }
                    if (activeSession === exitSession) activeSession = null
                }
            }

            val entryHost = target.host.trim()
            emitOn(
                entryHost,
                if (target.cascadeEnabled) 0.50f else 0.08f,
                "Подключение SSH, снятие входного стека…",
            )
            session = SshClient.connect(
                host = entryHost,
                user = target.sshUser.trim().ifBlank { "root" },
                port = target.sshPort,
                auth = target.auth(),
            )
            activeSession = session
            val ssh = SshClient(session, target.sudoPassword.ifBlank { target.password })
            append("SSH подключено ($entryHost)")
            TelemetryBridge.deploy("ssh_connected", activeHost)
            wipeRemoteStack(
                ssh,
                entryHost,
                progressStart = if (target.cascadeEnabled) 0.52f else 0.10f,
                progressEnd = 0.92f,
            )
            markTrackedHostDone(entryHost)

            throwIfCancelled()
            ServersRepository.get(appContext).delete(target.id)

            val msg = if (target.cascadeEnabled) {
                "Стек снят с $entryHost и ${target.cascadeHost.trim()}. Карточка удалена."
            } else {
                "Стек снят с $entryHost. Карточка удалена."
            }
            append(msg)
            finishHopsSuccess()
            emit(1f, msg)
            TelemetryBridge.deploy(
                "uninstall_succeeded",
                activeHost,
                JSONObject().put("message", msg),
            )
            _outcome.value = msg
            Result.success(msg)
        } catch (t: Throwable) {
            AppLog.e(TAG, "uninstall failed: ${t.message ?: t.javaClass.simpleName}")
            TelemetryBridge.handledError("uninstall", t)
            val cancelled = _log.value.any { it.contains("Отменено") }
            val msg = if (cancelled) {
                "Отменено"
            } else {
                t.message?.take(300) ?: t.javaClass.simpleName
            }
            append("Ошибка: $msg")
            emit(_progress.value, if (cancelled) "Отменено" else "Ошибка")
            TelemetryBridge.deploy(
                "uninstall_failed",
                activeHost,
                JSONObject()
                    .put("message", msg)
                    .put("progress", _progress.value.toDouble())
                    .put("step", _step.value)
                    .put("log_tail", _log.value.takeLast(20).joinToString("\n")),
            )
            val shown = if (cancelled) "Отменено" else "Ошибка: $msg"
            _outcome.value = shown
            Result.failure(t)
        } finally {
            runCatching { session?.disconnect() }
            activeSession = null
            activeHost = ""
        }
    }

    private fun wipeRemoteStack(
        ssh: SshClient,
        hostLabel: String,
        progressStart: Float,
        progressEnd: Float,
    ) {
        throwIfCancelled()
        emitOn(hostLabel, progressStart, "Снятие стека: контейнеры, /opt/ardtt, leftover…")
        var sawDone = false
        var failed: String? = null
        val span = (progressEnd - progressStart).coerceAtLeast(0.05f)
        val code = ssh.execStreaming(ServerUninstall.remoteCommand(), ServerUninstall.TIMEOUT_MS) { line ->
            append(line)
            when {
                line.startsWith("ARDTT_PROGRESS|") -> {
                    val parts = line.split('|', limit = 3)
                    val frac = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val step = parts.getOrNull(2)?.take(160).orEmpty()
                    val mapped = (progressStart + span * frac.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    if (step.isNotBlank()) emitOn(hostLabel, mapped, step)
                }
                line.startsWith("ARDTT_ERROR|") -> failed = line.removePrefix("ARDTT_ERROR|")
                line.contains(ServerUninstall.DONE_MARKER) -> sawDone = true
            }
        }
        throwIfCancelled()
        if (failed != null) {
            TelemetryBridge.deploy(
                "uninstaller_error",
                hostLabel,
                JSONObject().put("message", failed),
            )
            error("$hostLabel: $failed")
        }
        if (code != 0) {
            val hint = _log.value.takeLast(8).joinToString(" ")
            val detail = if (code == -1) {
                " — SSH-сессия оборвалась во время удаления"
            } else {
                ""
            }
            TelemetryBridge.deploy(
                "uninstaller_exit",
                hostLabel,
                JSONObject()
                    .put("exit_code", code)
                    .put("log_tail", hint),
            )
            error("uninstall exit=$code на $hostLabel$detail")
        }
        if (!sawDone) error("$hostLabel: сервер не подтвердил снятие стека")
        append("Стек снят на $hostLabel")
    }

    private fun throwIfCancelled() {
        if (_log.value.any { it.contains("Отменено") }) error("Отменено")
    }

    fun cancel() {
        append("Отменено")
        TelemetryBridge.deploy("deploy_cancelled", activeHost)
        runCatching { activeSession?.disconnect() }
        activeSession = null
    }

    private fun uploadAndInstall(
        ssh: SshClient,
        hostLabel: String,
        publicHost: String,
        stackBytes: ByteArray,
        installBytes: ByteArray,
        deployVersion: String,
        command: String,
        progressStart: Float,
        progressEnd: Float,
    ): DeployInstallResult {
        emitOn(hostLabel, progressStart, "Подготовка каталога /opt/ardtt…")
        ssh.exec(
            "if [ -d /opt/nonamevpn ] && [ ! -e /opt/ardtt ]; then mv /opt/nonamevpn /opt/ardtt; fi; " +
                "mkdir -p /opt/ardtt && chmod 755 /opt/ardtt",
        )

        val span = (progressEnd - progressStart).coerceAtLeast(0.05f)
        emitOn(
            hostLabel,
            progressStart + span * 0.08f,
            "Загрузка архива стека на VPS (${stackBytes.size / 1024} КБ)…",
        )
        ssh.uploadBytes(stackBytes, "/opt/ardtt/stack.tar.gz")
        append("Загружен stack.tar.gz на $hostLabel (${stackBytes.size / 1024} КБ)")

        emitOn(hostLabel, progressStart + span * 0.16f, "Загрузка install.sh и метки версии…")
        ssh.uploadBytes(installBytes, "/opt/ardtt/install.sh")
        ssh.exec("chmod +x /opt/ardtt/install.sh")
        runCatching {
            ssh.uploadBytes(
                (deployVersion + "\n").toByteArray(Charsets.UTF_8),
                "/opt/ardtt/DEPLOY_VERSION",
            )
        }
        append("Версия деплоя $deployVersion · $publicHost")
        TelemetryBridge.deploy(
            "bundle_uploaded",
            hostLabel,
            JSONObject()
                .put("deploy_version", deployVersion)
                .put("archive_bytes", stackBytes.size)
                .put("public_host", publicHost),
        )

        emitOn(hostLabel, progressStart + span * 0.22f, "Запуск install.sh…")
        var failed: String? = null
        var cascadePub = ""
        var doneFields = emptyMap<String, String>()
        val code = ssh.execStreaming(command, timeoutMs = 45 * 60_000L) { line ->
            append(line)
            DeployInstallEnv.publicKeyFromLine(line)?.let { cascadePub = it }
            when {
                line.startsWith("ARDTT_PROGRESS|") -> {
                    val parts = line.split('|', limit = 3)
                    val frac = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                    val step = parts.getOrNull(2)?.take(160).orEmpty()
                    val mapped = (progressStart + span * frac.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    if (step.isNotBlank()) emitOn(hostLabel, mapped, step)
                }
                line.startsWith("ARDTT_ERROR|") -> failed = line.removePrefix("ARDTT_ERROR|")
                line.startsWith("ARDTT_DONE|") -> {
                    doneFields = DeployInstallEnv.doneFields(line)
                    emitOn(hostLabel, progressEnd, "install.sh завершился")
                }
            }
        }
        if (failed != null) {
            TelemetryBridge.deploy(
                "installer_error",
                hostLabel,
                JSONObject().put("message", failed),
            )
            error("$hostLabel: $failed")
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
                hostLabel,
                JSONObject()
                    .put("exit_code", code)
                    .put("log_tail", hint),
            )
            error("install.sh exit=$code на $hostLabel$detail")
        }
        runCatching {
            ssh.exec(
                "rm -f /opt/ardtt/stack.tar.gz /var/log/ardtt-build*.log /var/log/ardtt-install.log; " +
                    "docker builder prune -af >/dev/null 2>&1 || true; " +
                    "docker image prune -f >/dev/null 2>&1 || true",
            )
        }
        return DeployInstallResult(
            cascadePublicKey = cascadePub,
            directPort = DeployInstallEnv.intField(doneFields, "direct_port"),
            bypassPort = DeployInstallEnv.intField(doneFields, "bypass_port"),
            cascadeListenPort = DeployInstallEnv.intField(doneFields, "cascade_listen_port"),
        )
    }

    private fun emitOn(host: String, fraction: Float, detail: String) {
        trackHost(host)
        emit(fraction, DeployProgressCopy.step(_hopTrack.value, host, detail))
    }

    private fun trackHost(host: String) {
        val trimmed = host.trim()
        activeHost = trimmed
        _hopTrack.value = _hopTrack.value.copy(activeHost = trimmed)
    }

    private fun markTrackedHostDone(host: String) {
        val cur = _hopTrack.value
        val trimmed = host.trim()
        _hopTrack.value = when {
            !cur.cascade -> cur.copy(entryDone = true, activeHost = trimmed)
            DeployHop.same(trimmed, cur.exitHost) -> cur.copy(exitDone = true, activeHost = trimmed)
            else -> cur.copy(entryDone = true, activeHost = trimmed)
        }
    }

    private fun finishHopsSuccess() {
        val cur = _hopTrack.value
        _hopTrack.value = cur.copy(
            entryDone = true,
            exitDone = cur.cascade,
            activeHost = "",
        )
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

        @Volatile
        private var instance: DeployEngine? = null

        fun get(context: Context): DeployEngine {
            return instance ?: synchronized(this) {
                instance ?: DeployEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
