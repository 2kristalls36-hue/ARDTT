package com.ardtt.app.deploy

/**
 * Structured deploy failure. The UI must not classify errors by the Russian
 * word «Ошибка».
 */
data class DeployIssue(
    val code: String,
    val summary: String,
    val detail: String = "",
    val hopRole: String? = null,
    val hopHost: String? = null,
    val entryInstallStarted: Boolean = false,
) {
    val isCancelled: Boolean get() = code == CANCELLED

    companion object {
        const val CANCELLED = "CANCELLED"
        const val SSH_FAILED = "SSH_FAILED"
        const val DOCKER_MISSING = "DOCKER_MISSING"
        const val DOCKER_NOT_RUNNING = "DOCKER_NOT_RUNNING"
        const val DOCKER_ACCESS_DENIED = "DOCKER_ACCESS_DENIED"
        const val PYTHON_MISSING = "PYTHON_MISSING"
        const val UNSUPPORTED_RUNTIME = "UNSUPPORTED_RUNTIME"
        const val PREFLIGHT_FAILED = "PREFLIGHT_FAILED"
        const val DISK_FULL = "DISK_FULL"
        const val INSTALL_FAILED = "INSTALL_FAILED"
        const val BUSY = "BUSY"

        fun parseArdttError(raw: String): Pair<String?, String> {
            val line = raw.trim()
            val payload = when {
                line.startsWith("ARDTT_ERROR|") -> line.removePrefix("ARDTT_ERROR|")
                else -> line
            }
            if (payload.startsWith("code=")) {
                val rest = payload.removePrefix("code=")
                val cut = rest.indexOf('|')
                if (cut > 0) {
                    val code = rest.substring(0, cut).trim()
                    var msg = rest.substring(cut + 1).trim()
                    if (msg.startsWith("message=")) msg = msg.removePrefix("message=")
                    return code to msg
                }
                return rest.trim() to rest.trim()
            }
            return null to payload
        }

        fun fromInstallerLine(
            raw: String,
            hopRole: String?,
            hopHost: String?,
            entryInstallStarted: Boolean,
        ): DeployIssue {
            val (code, message) = parseArdttError(raw)
            val resolved = when {
                !code.isNullOrBlank() -> code
                message.contains("Мало места") ||
                    message.contains("no space", ignoreCase = true) -> DISK_FULL
                else -> INSTALL_FAILED
            }
            return of(
                code = resolved,
                message = message.ifBlank { raw },
                hopRole = hopRole,
                hopHost = hopHost,
                entryInstallStarted = entryInstallStarted,
                detail = raw,
            )
        }

        /** True when the operator can opt into safe VPS disk reclaim and retry. */
        fun offersDiskCleanup(issue: DeployIssue?): Boolean {
            if (issue == null) return false
            if (issue.code == DISK_FULL) return true
            val text = "${issue.summary} ${issue.detail}"
            return text.contains("Мало места") ||
                text.contains("DISK_FULL") ||
                text.contains("no space", ignoreCase = true)
        }

        fun of(
            code: String,
            message: String,
            hopRole: String? = null,
            hopHost: String? = null,
            entryInstallStarted: Boolean = false,
            detail: String = message,
        ): DeployIssue {
            val summary = userSummary(
                code = code,
                message = message,
                hopRole = hopRole,
                entryInstallStarted = entryInstallStarted,
            )
            return DeployIssue(
                code = code,
                summary = summary,
                detail = detail,
                hopRole = hopRole,
                hopHost = hopHost,
                entryInstallStarted = entryInstallStarted,
            )
        }

        fun userSummary(
            code: String,
            message: String,
            hopRole: String?,
            entryInstallStarted: Boolean,
        ): String {
            val hop = when (hopRole) {
                "exit" -> "VPS2"
                "entry" -> "VPS1"
                else -> "VPS"
            }
            val entryNote = if (hopRole == "exit" && !entryInstallStarted) {
                " Установка ARDTT на VPS1 ещё не запускалась."
            } else {
                ""
            }
            return when (code) {
                CANCELLED -> "Отменено"
                BUSY -> "Деплой уже идёт"
                DOCKER_MISSING ->
                    "$hop: не удалось поставить Docker Engine из архива.$entryNote"
                DOCKER_NOT_RUNNING ->
                    "$hop: Docker установлен, но демон не отвечает.$entryNote"
                DOCKER_ACCESS_DENIED ->
                    "$hop: нет доступа к Docker. Запустите установку от root или в группе docker.$entryNote"
                PYTHON_MISSING ->
                    "$hop: нужен python3 на хосте. Пакет не ставит его из сети.$entryNote"
                UNSUPPORTED_RUNTIME ->
                    "$hop: обнаружен чужой контейнерный runtime. Это не чистый VPS.$entryNote"
                SSH_FAILED ->
                    "Не удалось открыть SSH${hopHostPart(hopRole, hop)}."
                PREFLIGHT_FAILED ->
                    "$hop не прошёл предварительную проверку."
                DISK_FULL ->
                    "$hop: мало места на диске. Можно очистить кэш/логи и повторить.$entryNote"
                else -> message.ifBlank { "$hop: установка не завершилась." }
            }
        }

        private fun hopHostPart(hopRole: String?, hop: String): String =
            if (hopRole == null) "" else " ($hop)"

        fun looksFailed(status: String?): Boolean {
            val text = status?.trim().orEmpty()
            if (text.isEmpty() || text == "Отменено" || text == "Готово") return false
            if (text.startsWith("ARDTT_ERROR|")) return true
            if (text.startsWith("Ошибка")) return true
            val code = parseArdttError(text).first
            return code != null && code != CANCELLED
        }

        fun redactLog(text: String): String {
            var out = text
            out = out.replace(Regex("(?i)(password|passwd|token|secret|passphrase)=\\S+"), "$1=***")
            out = out.replace(
                Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
                "[redacted-key]",
            )
            return out
        }
    }
}

class DeployIssueException(
    val issue: DeployIssue,
) : IllegalStateException(issue.summary)
