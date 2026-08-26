package com.nonamevpn.app.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkUrlTest {
    @Test
    fun stripsJoinLinks() {
        val hash = "LrSXnSsyDo_yNx28kZQp9GBC-8T7xjCAx4D0tJ2paLI"
        assertEquals(hash, VkUrl.strip("https://vk.com/call/join/$hash"))
        assertEquals(hash, VkUrl.strip("https://m.vk.ru/call/join/$hash?foo=1"))
        assertEquals(hash, VkUrl.strip(hash))
    }

    @Test
    fun plausibility() {
        assertTrue(VkUrl.isPlausibleHash("LrSXnSsyDo_yNx28kZQp9GBC-8T7xjCAx4D0tJ2paLI"))
        assertFalse(VkUrl.isPlausibleHash("short"))
    }

    @Test
    fun extractAccessToken() {
        val t = VkCallHashGenerator.extractAccessToken(
            "https://oauth.vk.com/blank.html#access_token=abc123&expires_in=0",
        )
        assertEquals("abc123", t)
    }
}
