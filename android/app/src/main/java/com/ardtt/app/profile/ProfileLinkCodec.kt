package com.ardtt.app.profile

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted profile deep links: `ardtt://config?v=1&n=<name>&p=<payload>`.
 *
 * Payload is AES-256-GCM over UTF-8 profile JSON (IV || ciphertext+tag), base64url.
 * Obfuscates keys in transit; recipients need ARDTT to import.
 */
object ProfileLinkCodec {
    const val SCHEME = "ardtt"
    const val HOST_CONFIG = "config"
    private const val VERSION = 1
    private const val PARAM_VERSION = "v"
    private const val PARAM_NAME = "n"
    private const val PARAM_PAYLOAD = "p"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val linkKey: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("ARDTT-profile-link-v1".toByteArray(StandardCharsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    fun buildLink(profile: VpnProfile): String {
        val json = VpnProfileJson.encode(profile, pretty = false)
        val encrypted = encrypt(json.toByteArray(StandardCharsets.UTF_8))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
        val name = URLEncoder.encode(profile.name, StandardCharsets.UTF_8.name())
        val encPayload = URLEncoder.encode(payload, StandardCharsets.UTF_8.name())
        return "$SCHEME://$HOST_CONFIG?$PARAM_VERSION=$VERSION&$PARAM_NAME=$name&$PARAM_PAYLOAD=$encPayload"
    }

    fun parseLink(uri: String): VpnProfile {
        val trimmed = uri.trim()
        require(trimmed.startsWith("$SCHEME://", ignoreCase = true)) {
            "Ожидалась ссылка ardtt://"
        }
        val withoutScheme = trimmed.substringAfter("://")
        val host = withoutScheme.substringBefore('?').substringBefore('/')
        require(host.equals(HOST_CONFIG, ignoreCase = true)) {
            "Неизвестный тип ссылки: $host"
        }
        val query = withoutScheme.substringAfter('?', "")
        val params = parseQuery(query)
        val version = params[PARAM_VERSION]?.toIntOrNull() ?: 1
        require(version == VERSION) { "Версия ссылки не поддерживается: $version" }
        val payloadEnc = params[PARAM_PAYLOAD]
            ?: error("В ссылке нет зашифрованного профиля")
        val payloadB64 = URLDecoder.decode(payloadEnc, StandardCharsets.UTF_8.name())
        val bytes = Base64.getUrlDecoder().decode(payloadB64)
        val json = String(decrypt(bytes), StandardCharsets.UTF_8)
        return VpnProfileJson.parse(json)
    }

    fun looksLikeLink(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("$SCHEME://", ignoreCase = true)
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, linkKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val out = cipher.doFinal(plain)
        return iv + out
    }

    private fun decrypt(combined: ByteArray): ByteArray {
        require(combined.size > IV_BYTES + 16) { "Повреждённая ссылка" }
        val iv = combined.copyOfRange(0, IV_BYTES)
        val body = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, linkKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            key to value
        }.toMap()
    }
}
