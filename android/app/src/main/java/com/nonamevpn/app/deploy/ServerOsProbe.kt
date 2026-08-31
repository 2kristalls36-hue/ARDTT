package com.nonamevpn.app.deploy

import java.util.Locale

data class ServerOsInfo(
    val osId: String,
    val osVersionLabel: String,
) {
    val isRecognized: Boolean get() = isRecognizedServerOsId(osId)
}

private val recognizedServerOsIds = setOf(
    "ubuntu",
    "debian",
    "centos",
    "rhel",
    "rocky",
    "almalinux",
    "fedora",
    "alpine",
    "arch",
    "opensuse",
)

fun isRecognizedServerOsId(osId: String): Boolean = osId.trim().lowercase(Locale.US) in recognizedServerOsIds

object ServerOsProbe {
    fun probe(target: DeployTarget): Result<ServerOsInfo> = runCatching {
        val session = SshClient.connect(
            host = target.host.trim(),
            user = target.sshUser.trim().ifBlank { "root" },
            port = target.sshPort,
            auth = target.auth(),
        )
        try {
            val client = SshClient(session, sudoPassword = target.sudoPassword.ifBlank { target.password })
            val output = client.exec("cat /etc/os-release", timeoutMs = 20_000L)
            parse(output)
        } finally {
            runCatching { session.disconnect() }
        }
    }

    internal fun parse(raw: String): ServerOsInfo {
        val values = linkedMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0) return@forEach
            val key = trimmed.substring(0, idx).trim()
            val value = unquote(trimmed.substring(idx + 1).trim())
            values[key] = value
        }
        val id = values["ID"].orEmpty().trim().lowercase(Locale.US)
        val pretty = values["PRETTY_NAME"].orEmpty().trim()
        val name = values["NAME"].orEmpty().trim()
        val version = values["VERSION_ID"].orEmpty().trim()
        val fallback = listOf(name, version).filter { it.isNotBlank() }.joinToString(" ")
        val label = pretty.ifBlank { fallback }
        return ServerOsInfo(
            osId = id,
            osVersionLabel = label,
        )
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
        }
        return value
    }
}
