package com.ardtt.app.bypass

/** Single invert control for VK login / logout in «Метод обхода». */
data class VkSessionAction(
    val label: String,
    val destructive: Boolean,
    val enabled: Boolean,
)

fun vkSessionAction(
    loggedIn: Boolean,
    vpnActive: Boolean,
    busy: Boolean,
    hasProfile: Boolean,
): VkSessionAction {
    val idle = !vpnActive && !busy
    return if (loggedIn) {
        VkSessionAction(
            label = "Завершить сессию",
            destructive = true,
            enabled = idle,
        )
    } else {
        VkSessionAction(
            label = "Авторизация",
            destructive = false,
            enabled = idle && hasProfile,
        )
    }
}

/**
 * Failed login that started logged-out often leaves a partial remixsid.
 * Clear it so the CTA stays on «Авторизация» instead of flipping to logout.
 */
fun vkShouldClearPartialSession(startedLoggedIn: Boolean, attemptSucceeded: Boolean): Boolean =
    !attemptSucceeded && !startedLoggedIn
