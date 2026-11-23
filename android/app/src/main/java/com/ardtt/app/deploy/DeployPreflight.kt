package com.ardtt.app.deploy

/**
 * Read-only host probe over SSH before deploy v2 (server/deploy/ardtt-deploy.sh).
 * A working Docker is used as is. Without Docker the installer takes it from
 * the distribution repository (docker.io / moby-engine / docker): preflight
 * only checks that such a package exists here (not on RHEL/Alma/Rocky).
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

    /** Probed only when Docker is absent; false when the key was never emitted. */
    val iptablesMissing: Boolean get() = fields["iptables"] == "0"
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
            "if [ -e /dev/net/tun ]; then echo \"ARDTT_PREFLIGHT|tun=1\"; else echo \"ARDTT_PREFLIGHT|tun=0\"; fi; " +
            "foreign=0; " +
            "if command -v podman >/dev/null 2>&1; then foreign=1; echo \"ARDTT_PREFLIGHT|podman=1\"; fi; " +
            "if command -v kubelet >/dev/null 2>&1; then foreign=1; echo \"ARDTT_PREFLIGHT|kubelet=1\"; fi; " +
            "if [ -S /run/containerd/containerd.sock ] && ! command -v docker >/dev/null 2>&1; then foreign=1; echo \"ARDTT_PREFLIGHT|containerd=1\"; fi; " +
            "case \"\$arch\" in x86_64|amd64|aarch64|arm64) ;; *) echo \"ARDTT_PREFLIGHT_DONE|ok=0|code=UNSUPPORTED_ARCH|message=\$arch\"; exit 0;; esac; " +
            "if ! command -v docker >/dev/null 2>&1; then " +
            "  if [ \"\$foreign\" = 1 ]; then echo \"ARDTT_PREFLIGHT_DONE|ok=0|code=UNSUPPORTED_RUNTIME|message=foreign-runtime\"; exit 0; fi; " +
            "  pkg=; " +
            "  if command -v apt-get >/dev/null 2>&1; then pkg=docker.io; " +
            "  elif command -v dnf >/dev/null 2>&1 && [ \"\$os_id\" = fedora ]; then pkg=moby-engine; " +
            "  elif command -v apk >/dev/null 2>&1 || command -v zypper >/dev/null 2>&1; then pkg=docker; fi; " +
            "  echo \"ARDTT_PREFLIGHT|docker=missing\"; " +
            "  if [ -z \"\$pkg\" ]; then echo \"ARDTT_PREFLIGHT_DONE|ok=0|code=DOCKER_MISSING|message=no-distro-docker\"; exit 0; fi; " +
            "  echo \"ARDTT_PREFLIGHT|docker_pkg=\$pkg\"; " +
            "  echo \"ARDTT_PREFLIGHT_DONE|ok=1|code=OK|message=docker-from-distro\"; exit 0; " +
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
            docker = when {
                fields["docker"] == "present" && ok -> "ok"
                fields["docker"] == "present" -> "present"
                else -> "missing"
            },
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
 * Deploy v2: a host without Docker gets it from the distribution repository
 * during install (ardtt-deploy.sh). There is no separate «prepare VPS» step.
 */
object DeployRuntimeBundle {
    const val INCLUDED = true

    fun canPrepare(osId: String, osVersion: String, arch: String): Boolean {
        val linuxArch = ServerOsProbe.linuxArch(arch)
        return linuxArch == "amd64" || linuxArch == "arm64"
    }

    fun missingRuntimeMessage(osId: String, osVersion: String, arch: String): String {
        val label = listOf(osId, osVersion, arch).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "этой ОС" }
        return "На $label нет Docker: установщик поставит его из репозитория дистрибутива. " +
            "Работающий Docker ARDTT не обновляет и не перезапускает."
    }
}
