package com.nonamevpn.app.unlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUnlockCopyTest {
    @Test
    fun explainsClosedBetaWithoutNamingTheProduct() {
        val text = DeviceUnlockCopy.userFacingStrings.joinToString("\n").lowercase()
        for (banned in listOf(
            "альфа",
            "alpha",
            "утечк",
            "apk",
            "как есть",
            "офлайн-код",
            "vpn",
            "туннел",
            "wireguard",
            "amnezia",
        )) {
            assertFalse("unexpected '$banned' in unlock copy:\n$text", text.contains(banned))
        }
        assertTrue(text.contains("подтвержд"))
        assertTrue(text.contains("устройств"))
        assertTrue(text.contains("бета"))
        assertTrue(text.contains("автор"))
        assertTrue(text.contains("шестизнач") || text.contains("шесть цифр"))
    }

    @Test
    fun lockMessagesStayFormal() {
        assertTrue(DeviceUnlockCopy.wrongCode(0).startsWith("Код указан неверно"))
        assertTrue(DeviceUnlockCopy.wrongCode(30_000L).contains("Повтор"))
        assertTrue(DeviceUnlockCopy.locked(30_000L).startsWith("Слишком много попыток"))
    }
}
