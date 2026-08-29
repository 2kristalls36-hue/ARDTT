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
    val ttlSeconds: Int = 540, // ~9 min account cache
)

sealed class DialResult {
    data class Ok(val creds: TurnCredentials) : DialResult()
    data class NeedHash(val message: String = "Для обхода необходим код звонка на этом устройстве") : DialResult()
    data class NeedVkLogin(val message: String = "Для создания кода звонка требуется авторизация во ВКонтакте") : DialResult()
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
 * Anonymous vkcalls is performed inside go_client (libclient.so).
 * This Kotlin dialer remains for hash-check / UI policy; Connect Path B
 * launches the native process which runs vkcalls → legacy itself.
 */
class VkCallsDialer : VkDialer {
    override suspend fun obtainTurn(hash: String?, path: DialPath): DialResult {
        if (hash.isNullOrBlank()) return DialResult.NeedHash()
        return DialResult.Failed(
            "vkcalls выполняется в libclient.so при Connect (Path B)",
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
