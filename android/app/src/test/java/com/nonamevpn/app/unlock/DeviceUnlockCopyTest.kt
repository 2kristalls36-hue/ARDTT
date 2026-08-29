package com.nonamevpn.app.unlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUnlockCopyTest {
    @Test
    fun usesFormalWordingWithoutTestingJargon() {
        val text = DeviceUnlockCopy.userFacingStrings.joinToString("\n").lowercase()
        for (banned in listOf(
            "альфа",
            "alpha",
            "тест",
            "утечк",
            "apk",
            "как есть",
            "офлайн-код",
            "разработчик",
        )) {
            assertFalse("unexpected '$banned' in unlock copy:\n$text", text.contains(banned))
        }
        assertTrue(text.contains("подтвержд"))
        assertTrue(text.contains("устройств"))
        assertTrue(text.contains("шестизнач") || text.contains("шесть цифр"))
    }

    @Test
    fun lockMessagesStayFormal() {
        assertTrue(DeviceUnlockCopy.wrongCode(0).startsWith("Код указан неверно"))
        assertTrue(DeviceUnlockCopy.wrongCode(30_000L).contains("Повторите попытку"))
        assertTrue(DeviceUnlockCopy.locked(30_000L).startsWith("Превышено допустимое число попыток"))
    }
}
