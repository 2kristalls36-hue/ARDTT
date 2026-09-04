package com.ardtt.app.bypass

import org.junit.Assert.assertEquals
import org.junit.Test

class DialPathTest {
    @Test
    fun fromSettingMapsKnownNames() {
        assertEquals(DialPath.Auto, DialPath.fromSetting(null))
        assertEquals(DialPath.Auto, DialPath.fromSetting("auto"))
        assertEquals(DialPath.Auto, DialPath.fromSetting("AUTO"))
        assertEquals(DialPath.VkCalls, DialPath.fromSetting("vkcalls"))
        assertEquals(DialPath.Legacy, DialPath.fromSetting(" legacy "))
        assertEquals("auto", DialPath.Auto.toSetting())
        assertEquals("vkcalls", DialPath.VkCalls.toSetting())
        assertEquals("legacy", DialPath.Legacy.toSetting())
        assertEquals(DialPath.VkCalls, DialPath.fromSetting(DialPath.VkCalls.toSetting()))
    }
}
