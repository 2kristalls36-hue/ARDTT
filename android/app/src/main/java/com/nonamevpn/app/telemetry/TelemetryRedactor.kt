package com.nonamevpn.app.telemetry

/**
 * Removes credentials before operational logs are persisted or uploaded.
 * Public hosts, ports, public keys and device/network diagnostics are retained.
 */
object TelemetryRedactor {
    private val pemBlock = Regex(
        """-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val jsonSecret = Regex(
        """(?i)("(?:privateKey|password|sudoPassword|keyPassphrase|passphrase|token|access_token|refresh_token|remixsid|cookie|authorization)"\s*:\s*")([^"]*)(")""",
    )
    private val querySecret = Regex(
        """(?i)((?:access_token|refresh_token|token|password|passphrase)=)[^&\s]+""",
    )
    private val sensitiveHeader = Regex(
        """(?im)^(Authorization|Cookie|Set-Cookie|X-Auth-[^:]*):\s*.*$""",
    )
    private val sudoPasswordPipe = Regex(
        """(?i)echo\s+(['"]).*?\1\s*\|\s*sudo\s+-S""",
    )

    fun redact(raw: String, maxChars: Int = MAX_MESSAGE_CHARS): String {
        var result = raw
        result = pemBlock.replace(result, "[REDACTED_PRIVATE_KEY]")
        result = jsonSecret.replace(result, "$1[REDACTED]$3")
        result = querySecret.replace(result, "$1[REDACTED]")
        result = sensitiveHeader.replace(result, "$1: [REDACTED]")
        result = sudoPasswordPipe.replace(result, "echo [REDACTED] | sudo -S")
        if (result.length > maxChars) {
            val removed = result.length - maxChars
            result = result.take(maxChars) + "…[truncated $removed chars]"
        }
        return result
    }

    const val MAX_MESSAGE_CHARS = 2_048
}
