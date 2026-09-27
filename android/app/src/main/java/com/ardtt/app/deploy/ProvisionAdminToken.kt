package com.ardtt.app.deploy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Admin bearer for provision `/v1/users*`. The installer writes
 * `/opt/ardtt/data/admin.token` and prints it in `ARDTT_DONE` only the first
 * time. Later updates keep the file, so the phone reads it over the existing
 * SSH session when the local copy is missing.
 */
object ProvisionAdminToken {
    const val REMOTE_PATH = "/opt/ardtt/data/admin.token"

    fun normalize(raw: String): String? {
        val token = raw.trim()
        if (token.length !in 16..256) return null
        // Keep the file bytes: provision compares the bearer as-is, including case.
        if (token.any { it.isWhitespace() || it.code !in 0x21..0x7e }) return null
        return token
    }

    /** Last `ARDTT_ADMIN_TOKEN …` line. Stderr from sudo must not become the bearer. */
    fun fromRemoteOutput(out: String): String? {
        val mark = "ARDTT_ADMIN_TOKEN "
        val raw = out.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                val at = line.indexOf(mark)
                if (at < 0) null else line.substring(at + mark.length).trim()
            }
            .lastOrNull()
            .orEmpty()
        return normalize(raw)
    }

    fun read(ssh: SshClient): String {
        val quoted = SshClient.shellQuote(REMOTE_PATH)
        val script =
            "v=\$(tr -d '[:space:]' < $quoted) && printf 'ARDTT_ADMIN_TOKEN %s\\n' \"\$v\""
        return fromRemoteOutput(ssh.exec(script, timeoutMs = 12_000L))
            ?: error("На сервере нет admin.token provision")
    }

    suspend fun ensure(
        repo: ServersRepository,
        target: DeployTarget,
        force: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        if (!force) {
            cached(repo, target)?.let { return@withContext it }
        }
        val session = SshClient.connect(
            host = target.host.trim(),
            user = target.sshUser.trim().ifBlank { "root" },
            port = target.sshPort,
            auth = target.auth(),
            timeoutMs = 12_000,
        )
        try {
            val client = SshClient(session, target.sudoPassword.ifBlank { target.password })
            val token = read(client)
            val current = repo.snapshot().find { it.id == target.id } ?: target
            repo.upsert(current.copy(provisionAdminToken = token))
            token
        } finally {
            runCatching { session.disconnect() }
        }
    }

    fun cached(repo: ServersRepository, target: DeployTarget): String? {
        normalize(target.provisionAdminToken)?.let { return it }
        val stored = repo.snapshot().find { it.id == target.id } ?: return null
        return normalize(stored.provisionAdminToken)
    }

    fun forBase(servers: List<DeployTarget>, baseUrl: String): String {
        val want = baseUrl.trim().trimEnd('/')
        val match = servers.firstOrNull {
            ProvisionAdminApi.provisionBase(it).trimEnd('/') == want
        } ?: return ""
        return normalize(match.provisionAdminToken).orEmpty()
    }
}
