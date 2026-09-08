package com.ardtt.app.deploy

/**
 * Read-only host probe run over SSH before GitHub download / SFTP.
 * Does not install, upgrade, or restart Docker.
 */
data class DeployPreflightResult(
    val ok: Boolean,
    val code: String? = null,
    val message: String = "",
    val osId: String = "",
    val osVersion: String = "",
    val arch: String = "",
    val docker: String = "",
    val dockerVersion: String = "",
    val python: Boolean = false,
    val tun: Boolean = false,
    val fields: Map<String, String> = emptyMap(),
) {
    val dockerPresent: Boolean get() = docker == "ok"
}

object DeployPreflight {
    /**
     * Compact bash without single quotes so sudo `bash -c` quoting stays valid.
     * Prints ARDTT_PREFLIGHT|k=v lines, then ARDTT_PREFLIGHT_DONE|ok=0|1|code=...
     */
    const val REMOTE_SCRIPT: String =
        "set +e; " +
            "os_id=; os_ver=; " +
            "if [ -f /etc/os-release ]; then . /etc/os-release; os_id=\${ID:-}; os_ver=\${VERSION_ID:-}; fi; " +
            "arch=\$(uname -m 2>/dev/null || true); " +
            "echo \"ARDTT_PREFLIGHT|os_id=\$os_id\"; " +
            "echo \"ARDTT_PREFLIGHT|os_ver=\$os_ver\"; " +
            "echo \"ARDTT_PREFLIGHT|arch=\$arch\"; " +
            "if command -v python3 >/dev/null 2>&1; then echo \"ARDTT_PREFLIGHT|python=1\"; else echo \"ARDTT_PREFLIGHT|python=0\"; fi; " +
            "if [ -e /dev/net/tun ]; then echo \"ARDTT_PREFLIGHT|tun=1\"; else echo \"ARDTT_PREFLIGHT|tun=0\"; fi; " +
            "foreign=0; " +
            "if command -v podman >/dev/null 2>&1; then foreign=1; echo \"ARDTT_PREFLIGHT|podman=1\"; fi; " +
            "if command -v kubelet >/dev/null 2>&1; then foreign=1; echo \"ARDTT_PREFLIGHT|kubelet=1\"; fi; " +
            "if [ -S /run/containerd/containerd.sock ] && ! command -v docker >/dev/null 2>&1; then foreign=1; echo \"ARDTT_PREFLIGHT|containerd=1\"; fi; " +
            "if ! command -v docker >/dev/null 2>&1; then " +
            "  if [ \"\$foreign\" = 1 ]; then echo \"ARDTT_PREFLIGHT_DONE|ok=0|code=UNSUPPORTED_RUNTIME|message=foreign-runtime\"; " +
            "  else echo \"ARDTT_PREFLIGHT_DONE|ok=0|code=DOCKER_MISSING|message=docker-cli-missing\"; fi; " +
            "  exit 0; " +
            "fi; " +
            "echo \"ARDTT_PREFLIGHT|docker=present\"; " +
            "info=\$(docker info 2>&1); " +
            "if ! docker info >/dev/null 2>&1; then " +
            "  if printf %s \"\$info\" | grep -qiE \"permission denied|access denied|dial unix\"; then " +
            "    echo \"ARDTT_PREFLIGHT_DONE|ok=0|code=DOCKER_ACCESS_DENIED|message=docker-access\"; exit 0; " +
            "  fi; " +
            "  echo \"ARDTT_PREFLIGHT_DONE|ok=0|code=DOCKER_NOT_RUNNING|message=docker-daemon\"; exit 0; " +
            "fi; " +
            "ver=\$(docker version --format \"{{.Server.Version}}\" 2>/dev/null || true); " +
            "echo \"ARDTT_PREFLIGHT|docker_version=\$ver\"; " +
            "if ! command -v python3 >/dev/null 2>&1; then echo \"ARDTT_PREFLIGHT_DONE|ok=0|code=PYTHON_MISSING|message=python3\"; exit 0; fi; " +
            "echo \"ARDTT_PREFLIGHT_DONE|ok=1|code=OK|message=ready\""

    fun parse(output: String): DeployPreflightResult {
        val fields = linkedMapOf<String, String>()
        var doneOk: Boolean? = null
        var doneCode: String? = null
        var doneMessage = ""
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("ARDTT_PREFLIGHT_DONE|") -> {
                    val body = line.removePrefix("ARDTT_PREFLIGHT_DONE|")
                    body.split('|').forEach { part ->
                        val idx = part.indexOf('=')
                        if (idx <= 0) return@forEach
                        val k = part.substring(0, idx)
                        val v = part.substring(idx + 1)
                        when (k) {
                            "ok" -> doneOk = v == "1" || v.equals("true", ignoreCase = true)
                            "code" -> doneCode = v
                            "message" -> doneMessage = v
                        }
                    }
                }
                line.startsWith("ARDTT_PREFLIGHT|") -> {
                    val body = line.removePrefix("ARDTT_PREFLIGHT|")
                    val idx = body.indexOf('=')
                    if (idx > 0) {
                        fields[body.substring(0, idx)] = body.substring(idx + 1)
                    }
                }
            }
        }
        val code = doneCode
        val ok = doneOk == true && (code == null || code == "OK")
        return DeployPreflightResult(
            ok = ok,
            code = code?.takeUnless { it == "OK" },
            message = doneMessage,
            osId = fields["os_id"].orEmpty(),
            osVersion = fields["os_ver"].orEmpty(),
            arch = fields["arch"].orEmpty(),
            docker = if (ok) "ok" else if (fields["docker"] == "present") "present" else "missing",
            dockerVersion = fields["docker_version"].orEmpty(),
            python = fields["python"] == "1",
            tun = fields["tun"] == "1",
            fields = fields,
        )
    }

    fun run(ssh: SshClient, timeoutMs: Long = 25_000L): DeployPreflightResult {
        val lines = mutableListOf<String>()
        val code = ssh.execStreaming(REMOTE_SCRIPT, timeoutMs) { line ->
            lines += line
        }
        val parsed = parse(lines.joinToString("\n"))
        if (parsed.code != null || parsed.ok) return parsed
        return parsed.copy(
            ok = false,
            code = DeployIssue.PREFLIGHT_FAILED,
            message = "preflight-exit=$code",
        )
    }
}

/**
 * Docker Engine debs are not packed in ardtt-server-*.tar.gz yet.
 * Do not show a working «Подготовить VPS» until CI ships a verified runtime.
 */
object DeployRuntimeBundle {
    const val INCLUDED = false

    fun canPrepare(osId: String, osVersion: String, arch: String): Boolean {
        if (!INCLUDED) return false
        val os = osId.trim().lowercase()
        val ver = osVersion.trim()
        val linuxArch = ServerOsProbe.linuxArch(arch)
        return os == "ubuntu" &&
            (ver.startsWith("24.04") || ver.startsWith("26.04")) &&
            (linuxArch == "amd64" || linuxArch == "arm64")
    }

    fun missingRuntimeMessage(osId: String, osVersion: String, arch: String): String {
        val label = listOf(osId, osVersion, arch).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "этой ОС" }
        return "Автоматическая подготовка Docker для $label ещё не входит в архив ARDTT. " +
            "Установите Docker Engine из локальных пакетов на чистом Ubuntu 24.04/26.04 " +
            "(без get.docker.com и без apt на VPS из сети), затем повторите проверку. " +
            "Существующий Docker ARDTT не обновляет и не перезапускает."
    }
}
