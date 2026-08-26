package com.nonamevpn.app.bypass

/**
 * VK dial for TURN credentials.
 * Default: vkcalls (anonymous). Fallback: legacy (captcha path) — WebView later.
 */
enum class DialPath {
    Auto,
    VkCalls,
    Legacy,
}

data class TurnCredentials(
    val username: String,
    val credential: String,
    val urls: List<String>,
    val source: DialPath,
    val ttlSeconds: Int = 540, // ~9 min like qWDTT account cache
)

sealed class DialResult {
    data class Ok(val creds: TurnCredentials) : DialResult()
    data class NeedHash(val message: String = "Нужен hash звонка на этом устройстве") : DialResult()
    data class NeedVkLogin(val message: String = "Войдите в VK, чтобы создать звонок") : DialResult()
    data class Failed(val message: String, val path: DialPath) : DialResult()
}

interface VkDialer {
    suspend fun obtainTurn(hash: String?, path: DialPath = DialPath.Auto): DialResult
}

/**
 * Orchestrates vkcalls → legacy. Full HTTP/TLS fingerprint client lands with native module;
 * this Kotlin layer owns policy and surfaces clear UX errors.
 */
class AutoVkDialer(
    private val vkCalls: VkDialer = VkCallsDialer(),
    private val legacy: VkDialer = LegacyDialer(),
) : VkDialer {
    override suspend fun obtainTurn(hash: String?, path: DialPath): DialResult {
        if (hash.isNullOrBlank()) {
            return DialResult.NeedHash()
        }
        return when (path) {
            DialPath.VkCalls -> vkCalls.obtainTurn(hash, DialPath.VkCalls)
            DialPath.Legacy -> legacy.obtainTurn(hash, DialPath.Legacy)
            DialPath.Auto -> {
                when (val first = vkCalls.obtainTurn(hash, DialPath.VkCalls)) {
                    is DialResult.Ok -> first
                    is DialResult.NeedHash, is DialResult.NeedVkLogin -> first
                    is DialResult.Failed -> {
                        when (val second = legacy.obtainTurn(hash, DialPath.Legacy)) {
                            is DialResult.Ok -> second
                            else -> DialResult.Failed(
                                "Обход: vkcalls и legacy не дали TURN (${first.message})",
                                DialPath.Auto,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Anonymous vkcalls API path (api.vk.me). Implementation performs real HTTP when
 * native/TLS client is wired; until then returns a structured failure so Connect
 * can show a clear status (hash present ≠ credentials yet).
 */
class VkCallsDialer : VkDialer {
    override suspend fun obtainTurn(hash: String?, path: DialPath): DialResult {
        if (hash.isNullOrBlank()) return DialResult.NeedHash()
        // Placeholder until fhttp/tls-client or Kotlin HTTP with VK fingerprint lands.
        // Contract matches qWDTT: anonymous_token → getCallPreview → getAnonymCallToken → turn_server.
        return DialResult.Failed(
            "vkcalls: HTTP-клиент ещё не подключён (hash сохранён, ждём native/API слой)",
            DialPath.VkCalls,
        )
    }
}

/** Legacy captcha path — requires WebView account session later. */
class LegacyDialer : VkDialer {
    override suspend fun obtainTurn(hash: String?, path: DialPath): DialResult {
        if (hash.isNullOrBlank()) return DialResult.NeedHash()
        return DialResult.Failed(
            "legacy: WebView/captcha ещё не подключены",
            DialPath.Legacy,
        )
    }
}
