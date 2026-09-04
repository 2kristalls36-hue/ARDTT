package com.ardtt.app.tunnel

import com.ardtt.app.profile.DirectConfig
import org.amnezia.awg.crypto.Key
import org.amnezia.awg.crypto.KeyFormatException
import java.net.InetAddress
import java.util.Locale

/**
 * Builds AmneziaWG userspace (IpcSet) settings from a provision DirectConfig.
 * Keys in profile JSON are WireGuard-style base64; UAPI wants hex.
 */
object AwgUserspaceConfig {
    private val AWG_UAPI_KEYS = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5",
        "header_protection_key", "content_padding_addition",
        "rekey_after_time", "rekey_timeout", "reject_after_time",
        "keepalive_timeout", "max_handshake_attempts",
        "random_trailers", "disable_cookies",
    )

    fun build(direct: DirectConfig, keepaliveSeconds: Int = 25): String {
        val privateHex = keyToHex(direct.privateKey, "privateKey")
        val peerHex = keyToHex(direct.peerPublicKey, "peerPublicKey")
        val endpoint = resolveEndpoint(direct.endpoint)
            ?: throw IllegalArgumentException("Не удалось разрешить endpoint ${direct.endpoint}")

        val sb = StringBuilder()
        sb.append("private_key=").append(privateHex).append('\n')
        for ((rawKey, value) in direct.awg) {
            val key = normalizeAwgKey(rawKey) ?: continue
            if (value.isBlank()) continue
            sb.append(key).append('=').append(normalizeAwgValue(key, value)).append('\n')
        }
        sb.append("public_key=").append(peerHex).append('\n')
        sb.append("endpoint=").append(endpoint).append('\n')
        sb.append("allowed_ip=0.0.0.0/0\n")
        sb.append("persistent_keepalive_interval=").append(keepaliveSeconds).append('\n')
        return sb.toString()
    }

    private fun normalizeAwgKey(raw: String): String? {
        val k = raw.trim().lowercase(Locale.US)
        // Profile / conf use Jc, Jmin… — map to UAPI jc, jmin…
        return when (k) {
            in AWG_UAPI_KEYS -> k
            else -> null
        }
    }

    private fun normalizeAwgValue(key: String, value: String): String {
        if (key == "random_trailers" || key == "disable_cookies") {
            return when (value.trim().lowercase(Locale.US)) {
                "1", "true", "on", "yes" -> "true"
                else -> "false"
            }
        }
        if (key == "header_protection_key") {
            return runCatching { keyToHex(value, key) }.getOrDefault(value.trim())
        }
        return value.trim()
    }

    private fun keyToHex(raw: String, field: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Пустой $field")
        return try {
            if (trimmed.length == 64 && trimmed.all { it in "0123456789abcdefABCDEF" }) {
                trimmed.lowercase(Locale.US)
            } else {
                Key.fromBase64(trimmed).toHex()
            }
        } catch (e: KeyFormatException) {
            throw IllegalArgumentException("Неверный $field (нужен base64 ключ AWG)", e)
        }
    }

    /** Resolve host:port → ip:port for UAPI (amneziawg-go expects a resolved endpoint). */
    fun resolveEndpoint(endpoint: String, retries: Int = 10): String? {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return null
        val host: String
        val port: String
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close <= 1 || close + 1 >= trimmed.length || trimmed[close + 1] != ':') return null
            host = trimmed.substring(1, close)
            port = trimmed.substring(close + 2)
        } else {
            val idx = trimmed.lastIndexOf(':')
            if (idx <= 0) return null
            host = trimmed.substring(0, idx)
            port = trimmed.substring(idx + 1)
        }
        if (port.toIntOrNull() == null) return null
        // Already an IP?
        if (looksLikeIp(host)) return "$host:$port"
        repeat(retries) { attempt ->
            try {
                val addrs = InetAddress.getAllByName(host)
                val v4 = addrs.firstOrNull { it.address.size == 4 }
                val chosen = v4 ?: addrs.firstOrNull()
                if (chosen != null) return "${chosen.hostAddress}:$port"
            } catch (_: Exception) {
                // retry
            }
            if (attempt < retries - 1) Thread.sleep(500)
        }
        return null
    }

    private fun looksLikeIp(host: String): Boolean {
        if (host.contains(':')) return true // v6
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
