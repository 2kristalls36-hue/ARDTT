package com.nonamevpn.app.bypass

/** VK dial for TURN credentials. Native Path B (`libclient.so`) performs vkcalls → legacy. */
enum class DialPath {
    Auto,
    VkCalls,
    Legacy,
}
