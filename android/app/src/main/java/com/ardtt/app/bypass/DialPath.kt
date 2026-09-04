package com.ardtt.app.bypass

/** VK dial for TURN credentials. Native Path B (`libclient.so`) performs vkcalls → legacy. */
enum class DialPath {
    Auto,
    VkCalls,
    Legacy,
    ;

    fun toSetting(): String = when (this) {
        Auto -> "auto"
        VkCalls -> "vkcalls"
        Legacy -> "legacy"
    }

    companion object {
        fun fromSetting(raw: String?): DialPath = when (raw?.lowercase()?.trim()) {
            "vkcalls" -> VkCalls
            "legacy" -> Legacy
            else -> Auto
        }
    }
}
