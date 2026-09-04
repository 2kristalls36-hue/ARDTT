package com.ardtt.app.bypass

/**
 * Normalize VK join URLs / pasted hashes to the bare call hash used by go_client `-vk`.
 */
object VkUrl {
    private val JOIN_PREFIXES = listOf(
        "https://vk.com/call/join/",
        "http://vk.com/call/join/",
        "https://m.vk.com/call/join/",
        "http://m.vk.com/call/join/",
        "https://vk.ru/call/join/",
        "http://vk.ru/call/join/",
        "https://m.vk.ru/call/join/",
        "http://m.vk.ru/call/join/",
        "m.vk.com/call/join/",
        "vk.com/call/join/",
        "m.vk.ru/call/join/",
        "vk.ru/call/join/",
    )

    fun strip(input: String): String {
        var s = input.trim()
        val lower = s.lowercase()
        for (p in JOIN_PREFIXES) {
            if (lower.startsWith(p)) {
                s = s.substring(p.length)
                break
            }
        }
        s = s.substringBefore('?').substringBefore('#')
        return s.trimEnd('/')
    }

    fun isPlausibleHash(hash: String): Boolean = strip(hash).length >= 16
}
