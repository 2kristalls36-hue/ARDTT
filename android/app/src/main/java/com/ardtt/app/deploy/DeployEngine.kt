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
 * Admin deploy: probe VPS arch over SSH, stream one GitHub Release package
 * (`ardtt-server-<ver>-linux-<arch>.tar.gz`) to a cache file, SFTP it, run
 * the installer from that archive. No git/main/raw fallback, no docker prune.
 * Protocol and VPS layout: docs/DEPLOY.md.
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
            val payloads = mutableMapOf<String, DeployPayload>()
            fun payloadFor(arch: String, progressStart: Float, progressEnd: Float): DeployPayload {
                val linuxArch = DeployStackSource.linuxArch(arch)
                payloads[linuxArch]?.let { return it }
                emit(progressStart, "Загрузка пакета $deployVersion ($linuxArch) из GitHub…")
                val span = (progressEnd - progressStart).coerceAtLeast(0.02f)
                val payload = DeployStackFetcher(appContext, deployVersion).fetch(
                    arch = linuxArch,
                    onProgress = { frac ->
                        emit(
                            progressStart + span * frac.coerceIn(0f, 1f),
                            "Загрузка пакета $linuxArch из GitHub…",
                        )
                    },
                    isCancelled = { _log.value.any { it.contains("Отменено") } },
                )
                payloads[linuxArch] = payload
                append(
                    "Пакет $deployVersion $linuxArch ← ${payload.sourceLabel} (${payload.sizeBytes / 1024} КБ)",
                )
                TelemetryBridge.deploy(
                    "bundle_downloaded",
                    activeHost,
                    JSONObject()
                        .put("deploy_version", deployVersion)
                        .put("source", payload.sourceLabel)
                        .put("git_ref", payload.gitRef)
                        .put("arch", linuxArch)
                        .put("archive_bytes", payload.sizeBytes)
                        .put("sha256", payload.sha256),
                )
                return payload
            }
            val publicHost = target.publicHost.ifBlank { target.host }.trim()
            val entryHost = target.host.trim()
            val verb = if (_isUpdate.value) "обновление" else "установка"

            var exitPub = ""
            var exitCascadeListen = DeployInstallEnv.CASCADE_LISTEN_PORT
            var resolvedDirect = target.directPort
            var resolvedBypass = target.bypassPort
            var resolvedProvision = target.provisionPort
            var resolvedTelemetry = target.telemetryPort
            var entryArch = target.arch
            var exitArch = target.cascadeArch
            var exitProvision = target.cascadeProvisionPort
            var exitTelemetry = target.cascadeTelemetryPort
            if (target.cascadeEnabled) {
                val exitHost = target.cascadeHost.trim()
                if (exitHost.isBlank()) error("Не указан host второго сервера")
                emitOn(exitHost, 0.04f, "Подключение SSH, $verb выходного стека…")
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
                    exitArch = ServerOsProbe.probeLinuxArch(exitSsh)
                    append("Архитектура выхода: $exitArch")
                    val exitPayload = payloadFor(exitArch, 0.06f, 0.14f)
                    val installed = uploadAndInstall(
                        ssh = exitSsh,
                        hostLabel = exitHost,
                        publicHost = exitHost,
                        payload = exitPayload,
                        deployVersion = deployVersion,
                        command = DeployInstallEnv.command(
                            publicHost = exitHost,
                            directPort = target.directPort,
                            bypassPort = target.bypassPort,
                            deployVersion = deployVersion,
                            role = "exit",
                            packagePath = DeployInstallEnv.packageRemotePath(deployVersion, exitArch),
                            packageSha256 = exitPayload.sha256,
                            cascadeEnabled = true,
                            autoPorts = target.autoPorts,
                            provisionPort = target.cascadeProvisionPort,
                            telemetryPort = target.cascadeTelemetryPort,
                        ),
                        progressStart = 0.14f,
                        progressEnd = 0.48f,
                    )
                    exitPub = installed.cascadePublicKey
                    if (exitPub.isBlank()) {
                        error("Выходной VPS не отдал ключ каскада (ARDTT_CASCADE_PUBLIC_KEY)")
                    }
                    installed.cascadeListenPort?.let { exitCascadeListen = it }
                        ?: installed.directPort?.let { exitCascadeListen = it }
                    installed.provisionPort?.let { exitProvision = it }
                    installed.telemetryPort?.let { exitTelemetry = it }
                    append("Ключ выхода получен с $exitHost")
                    markTrackedHostDone(exitHost)
                } finally {
                    runCatching { exitSession.disconnect() }
                    if (activeSession === exitSession) activeSession = null
                }
            }

            emitOn(entryHost, if (target.cascadeEnabled) 0.50f else 0.04f, "Подключение SSH, $verb входного стека…")
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
            entryArch = ServerOsProbe.probeLinuxArch(ssh)
            append("Архитектура входа: $entryArch")
            val entryPayload = payloadFor(
                entryArch,
                if (target.cascadeEnabled) 0.50f else 0.06f,
                if (target.cascadeEnabled) 0.56f else 0.18f,
            )

            val entryCmd = DeployInstallEnv.command(
                publicHost = publicHost,
                directPort = target.directPort,
                bypassPort = target.bypassPort,
                deployVersion = deployVersion,
                role = "entry",
                packagePath = DeployInstallEnv.packageRemotePath(deployVersion, entryArch),
                packageSha256 = entryPayload.sha256,
                cascadeEnabled = target.cascadeEnabled,
                cascadePeerEndpoint = if (target.cascadeEnabled) {
                    DeployInstallEnv.peerEndpoint(target.cascadeHost, exitCascadeListen)
                } else {
                    ""
                },
                cascadePeerPublicKey = exitPub,
                cascadePeerProvisionPort = exitProvision,
                autoPorts = target.autoPorts,
                provisionPort = target.provisionPort,
                telemetryPort = target.telemetryPort,
            )
            val entryInstalled = uploadAndInstall(
                ssh = ssh,
                hostLabel = entryHost,
                publicHost = publicHost,
                payload = entryPayload,
                deployVersion = deployVersion,
                command = entryCmd,
                progressStart = if (target.cascadeEnabled) 0.56f else 0.18f,
                progressEnd = if (target.cascadeEnabled) 0.92f else 0.96f,
            )
            val entryPub = entryInstalled.cascadePublicKey
            entryInstalled.directPort?.let { resolvedDirect = it }
            entryInstalled.bypassPort?.let { resolvedBypass = it }
            entryInstalled.provisionPort?.let { resolvedProvision = it }
            entryInstalled.telemetryPort?.let { resolvedTelemetry = it }
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
                        "install -d -m 700 /opt/ardtt/data && " +
                            "printf '%s\\n' ${SshClient.shellQuote(entryPub)} " +
                            "> /opt/ardtt/data/cascade.peer.pub && " +
                            "chmod 644 /opt/ardtt/data/cascade.peer.pub && " +
                            "if [ -f /opt/ardtt/current/docker-compose.yml ]; then " +
                            "(cd /opt/ardtt/current && docker compose restart); " +
                            "elif [ -f /opt/ardtt/stack/docker-compose.yml ]; then " +
                            "(cd /opt/ardtt/stack && docker compose restart); fi",
                    )
                    append("Пир входа записан на $exitHost")
                } finally {
                    runCatching { exitSession.disconnect() }
                    activeSession = session
                }
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
                        provisionPort = resolvedProvision,
                        telemetryPort = resolvedTelemetry,
                        arch = entryArch.ifBlank { stored.arch },
                        cascadeProvisionPort = exitProvision,
                        cascadeTelemetryPort = exitTelemetry,
                        cascadeArch = exitArch.ifBlank { stored.cascadeArch },
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
        payload: DeployPayload,
        deployVersion: String,
        command: String,
        progressStart: Float,
        progressEnd: Float,
    ): DeployInstallResult {
        emitOn(hostLabel, progressStart, "Подготовка каталога /opt/ardtt/incoming…")
        ssh.exec(
            "if [ -d /opt/nonamevpn ] && [ ! -e /opt/ardtt ]; then mv /opt/nonamevpn /opt/ardtt; fi; " +
                "mkdir -p /opt/ardtt/incoming && chmod 755 /opt/ardtt /opt/ardtt/incoming",
        )

        val span = (progressEnd - progressStart).coerceAtLeast(0.05f)
        val remoteFinal = DeployInstallEnv.packageRemotePath(deployVersion, payload.arch)
        val remotePartial = "$remoteFinal.partial"
        emitOn(
            hostLabel,
            progressStart + span * 0.06f,
            "SFTP пакета на VPS (${payload.sizeBytes / 1024} КБ)…",
        )
        ssh.exec("rm -f ${SshClient.shellQuote(remotePartial)}")
        ssh.upload(payload.packageFile, remotePartial) { copied, total ->
            val frac = if (total > 0L) (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            emitOn(
                hostLabel,
                progressStart + span * (0.06f + 0.28f * frac),
                "SFTP пакета (${copied / 1024} / ${total / 1024} КБ)…",
            )
        }
        ssh.exec(
            "mv -f ${SshClient.shellQuote(remotePartial)} ${SshClient.shellQuote(remoteFinal)}",
        )
        append("Загружен ${payload.packageFile.name} на $hostLabel (${payload.sizeBytes / 1024} КБ)")
        TelemetryBridge.deploy(
            "bundle_uploaded",
            hostLabel,
            JSONObject()
                .put("deploy_version", deployVersion)
                .put("archive_bytes", payload.sizeBytes)
                .put("sha256", payload.sha256)
                .put("arch", payload.arch)
                .put("public_host", publicHost),
        )

        emitOn(hostLabel, progressStart + span * 0.36f, "Запуск install.sh из пакета…")
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
                    val mapped = (progressStart + span * (0.36f + 0.64f * frac.coerceIn(0f, 1f)))
                        .coerceIn(0f, 1f)
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
                        "Повторите деплой: предыдущий стек не переключается до readiness"
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
                "rm -f ${SshClient.shellQuote(remoteFinal)} ${SshClient.shellQuote(remotePartial)}",
            )
        }
        return DeployInstallResult(
            cascadePublicKey = cascadePub,
            directPort = DeployInstallEnv.intField(doneFields, "direct_port"),
            bypassPort = DeployInstallEnv.intField(doneFields, "bypass_port"),
            cascadeListenPort = DeployInstallEnv.intField(doneFields, "cascade_listen_port"),
            provisionPort = DeployInstallEnv.intField(doneFields, "provision_port"),
            telemetryPort = DeployInstallEnv.intField(doneFields, "telemetry_port"),
            instanceId = doneFields["instance"].orEmpty(),
            containerName = doneFields["container"].orEmpty(),
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
