package com.ardtt.app.deploy

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ServerOsInfo(
    val osId: String,
    val osVersionLabel: String,
) {
    val isRecognized: Boolean get() = isRecognizedServerOsId(osId)
}

internal val recognizedServerOsIds = setOf(
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

fun isRecognizedServerOsId(osId: String): Boolean =
    canonicalServerOsId(osId, idLike = "", name = "").let { it.isNotEmpty() && it in recognizedServerOsIds }

enum class ServerOsMark {
    Ubuntu,
    Debian,
    Fedora,
    Alpine,
    Arch,
    Rhel,
    Suse,
    Linux,
    Unknown,
}

fun serverOsMark(osId: String): ServerOsMark =
    when (canonicalServerOsId(osId, idLike = "", name = osId)) {
        "ubuntu" -> ServerOsMark.Ubuntu
        "debian" -> ServerOsMark.Debian
        "fedora" -> ServerOsMark.Fedora
        "alpine" -> ServerOsMark.Alpine
        "arch" -> ServerOsMark.Arch
        "centos", "rhel", "rocky", "almalinux" -> ServerOsMark.Rhel
        "opensuse" -> ServerOsMark.Suse
        "" -> ServerOsMark.Unknown
        else -> ServerOsMark.Linux
    }

fun serverOsBadgeLabel(osId: String): String {
    val id = canonicalServerOsId(osId, idLike = "", name = osId).ifBlank { osId.trim() }
    if (id.isBlank()) return "OS"
    return when (id) {
        "ubuntu" -> "Ubuntu"
        "debian" -> "Debian"
        "fedora" -> "Fedora"
        "alpine" -> "Alpine"
        "arch" -> "Arch"
        "centos" -> "CentOS"
        "rhel" -> "RHEL"
        "rocky" -> "Rocky"
        "almalinux" -> "Alma"
        "opensuse" -> "SUSE"
        else -> id.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
        }
    }
}

internal fun canonicalServerOsId(
    id: String,
    idLike: String = "",
    name: String = "",
): String {
    fun alias(raw: String): String? {
        val v = raw.trim().lowercase(Locale.US)
        if (v.isEmpty()) return null
        if (v in recognizedServerOsIds) return v
        return when (v) {
            "pop", "pop-os", "linuxmint", "zorin", "elementary",
            "kubuntu", "xubuntu", "lubuntu",
            -> "ubuntu"
            "ol", "oracle", "amzn", "amazon", "scientific" -> "rhel"
            "sles", "opensuse-leap", "opensuse-tumbleweed", "suse" -> "opensuse"
            "manjaro", "endeavouros" -> "arch"
            else -> null
        }
    }
    alias(id)?.let { return it }
    idLike.split(Regex("[\\s,]+")).forEach { token ->
        alias(token)?.let { return it }
    }
    val n = name.trim().lowercase(Locale.US)
    if (n.isNotEmpty()) {
        recognizedServerOsIds.firstOrNull { n.contains(it) }?.let { return it }
        alias(n)?.let { return it }
    }
    return id.trim().lowercase(Locale.US)
}

fun ServerOsInfo.appliedTo(target: DeployTarget): DeployTarget? {
    val nextId = osId.trim()
    val nextVersion = osVersionLabel.trim()
    if (nextId.isEmpty() && nextVersion.isEmpty()) return null
    if (target.osId == nextId && target.osVersion == nextVersion) return null
    return target.copy(
        osId = nextId.ifEmpty { target.osId },
        osVersion = nextVersion.ifEmpty { target.osVersion },
    )
}

object ServerOsProbe {
    suspend fun probe(target: DeployTarget): Result<ServerOsInfo> = withContext(Dispatchers.IO) {
        runCatching { probeBlocking(target) }
    }

    /** SSH login only — used to tell “not installed” from “unreachable”. */
    suspend fun authOk(target: DeployTarget, timeoutMs: Int = 8_000): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = SshClient.connect(
                    host = target.host.trim(),
                    user = target.sshUser.trim().ifBlank { "root" },
                    port = target.sshPort,
                    auth = target.auth(),
                    timeoutMs = timeoutMs,
                )
                runCatching { session.disconnect() }
                true
            }.getOrDefault(false)
        }

    suspend fun refreshStored(repo: ServersRepository, target: DeployTarget) {
        if (target.osId.isNotBlank()) return
        val info = probe(target).getOrNull() ?: return
        info.appliedTo(target)?.let { repo.upsert(it) }
    }

    internal fun probeBlocking(target: DeployTarget): ServerOsInfo {
        val session = SshClient.connect(
            host = target.host.trim(),
            user = target.sshUser.trim().ifBlank { "root" },
            port = target.sshPort,
            auth = target.auth(),
        )
        try {
            val client = SshClient(session, sudoPassword = target.sudoPassword.ifBlank { target.password })
            val output = client.exec("cat /etc/os-release", timeoutMs = 12_000L)
            return parse(output)
        } finally {
            runCatching { session.disconnect() }
        }
    }

    internal fun parse(raw: String): ServerOsInfo {
        val values = linkedMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim().trimEnd('\r')
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0) return@forEach
            val key = trimmed.substring(0, idx).trim()
            val value = unquote(trimmed.substring(idx + 1).trim())
            if (key.isNotEmpty()) values[key] = value
        }
        val pretty = values["PRETTY_NAME"].orEmpty().trim()
        val name = values["NAME"].orEmpty().trim()
        val version = values["VERSION_ID"].orEmpty().ifBlank { values["VERSION"].orEmpty() }.trim()
        val fallback = listOf(name, version).filter { it.isNotBlank() }.joinToString(" ")
        val label = pretty.ifBlank { fallback }
        val id = canonicalServerOsId(
            id = values["ID"].orEmpty(),
            idLike = values["ID_LIKE"].orEmpty(),
            name = name.ifBlank { pretty },
        )
        return ServerOsInfo(
            osId = id,
            osVersionLabel = label,
        )
    }

    private fun unquote(value: String): String {
        var v = value.trim()
        if (v.length >= 2) {
            val quote = v.first()
            if (quote == '"' || quote == '\'') {
                val end = v.indexOf(quote, startIndex = 1)
                if (end > 0) return v.substring(1, end)
            }
        }
        val hash = v.indexOf('#')
        if (hash >= 0) v = v.substring(0, hash).trim()
        return v
    }
}
