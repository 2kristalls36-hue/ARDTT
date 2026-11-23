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
        const val UNSUPPORTED_RUNTIME = "UNSUPPORTED_RUNTIME"
        const val PREFLIGHT_FAILED = "PREFLIGHT_FAILED"
        const val DISK_FULL = "DISK_FULL"
        const val INSUFFICIENT_DISK = "INSUFFICIENT_DISK"
        const val INSTALL_FAILED = "INSTALL_FAILED"
        const val BUSY = "BUSY"
        const val DEPLOY_IN_PROGRESS = "DEPLOY_IN_PROGRESS"
        const val DISK_MEASUREMENT_FAILED = "DISK_MEASUREMENT_FAILED"
        const val READINESS_FAILED = "READINESS_FAILED"
        const val ROLLBACK_FAILED = "ROLLBACK_FAILED"

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
                code == DEPLOY_IN_PROGRESS -> BUSY
                code == DISK_MEASUREMENT_FAILED -> INSUFFICIENT_DISK
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

        /**
         * Deploy v2 never cleans the host (other services share its disk), so
         * there is no «retry with cleanup»: the error says how much to free.
         */
        @Suppress("UNUSED_PARAMETER")
        fun offersDiskCleanup(issue: DeployIssue?): Boolean = false

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
            val said = installerSentence(message)
            return when (code) {
                CANCELLED -> "Отменено"
                BUSY, DEPLOY_IN_PROGRESS -> "Деплой уже идёт"
                DOCKER_MISSING ->
                    "$hop: в репозиториях этой системы нет Docker (RHEL/Alma/Rocky). " +
                        "Установите Docker Engine вручную и повторите.$entryNote"
                DOCKER_NOT_RUNNING ->
                    "$hop: Docker установлен, но демон не отвечает. Запустите его (systemctl start docker) и повторите.$entryNote"
                DOCKER_ACCESS_DENIED ->
                    "$hop: нет доступа к Docker. Запустите установку от root или с sudo.$entryNote"
                UNSUPPORTED_RUNTIME ->
                    "$hop: на сервере другой контейнерный runtime (podman/kubelet) без Docker.$entryNote"
                SSH_FAILED ->
                    "Не удалось открыть SSH${hopHostPart(hopRole, hop)}."
                PREFLIGHT_FAILED ->
                    "$hop не прошёл предварительную проверку."
                DISK_FULL, INSUFFICIENT_DISK ->
                    "$hop: ${said.ifBlank { "мало места на диске." }}$entryNote"
                READINESS_FAILED, ROLLBACK_FAILED ->
                    "$hop: ${said.ifBlank { "новая версия не запустилась." }}$entryNote"
                else -> if (said.isNotBlank()) "$hop: $said$entryNote" else "$hop: установка не завершилась.$entryNote"
            }
        }

        /**
         * The installer's messages are already written for people; drop the
         * diagnostic tail (container state, log excerpt) that goes to details.
         */
        fun installerSentence(message: String): String {
            var text = message.trim()
            for (cut in listOf(" state=", " журнал:")) {
                val i = text.indexOf(cut)
                if (i > 0) text = text.substring(0, i).trim()
            }
            return text.take(300)
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
            out = out.replace(
                Regex("(?i)(password|passwd|token|secret|passphrase)=[^|\\s]+"),
                "$1=***",
            )
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
