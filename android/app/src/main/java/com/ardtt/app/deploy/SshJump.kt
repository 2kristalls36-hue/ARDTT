package com.ardtt.app.deploy

/** How cascade deploy reaches VPS 2: never from the phone, always via VPS 1. */
internal object SshJump {
    fun targetHost(raw: String): String =
        DeployHop.host(raw)?.ifBlank { null } ?: raw.trim()

    fun connectError(entryHost: String, exitHost: String, cause: Throwable): String {
        val detail = cause.message?.trim().orEmpty().ifBlank { cause.javaClass.simpleName }
        return "Не удалось открыть SSH к $exitHost через $entryHost. " +
            "С входного VPS должен быть доступен порт SSH выхода " +
            "(sshd AllowTcpForwarding). $detail"
    }
}
