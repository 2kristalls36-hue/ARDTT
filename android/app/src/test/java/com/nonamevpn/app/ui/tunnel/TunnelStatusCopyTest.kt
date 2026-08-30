package com.nonamevpn.app.ui.tunnel

import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.VpnPath
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelStatusCopyTest {
    @Test
    fun statusIsShortAndOmitsPath() {
        assertEquals(
            "Подключено",
            sessionCardStatusText(ConnState.Connected, publicIp = "1.1.1.1"),
        )
        assertEquals(
            "Подключение…",
            sessionCardStatusText(ConnState.Connected, publicIp = null),
        )
        assertEquals(
            "Подключение…",
            sessionCardStatusText(ConnState.Connecting, publicIp = null),
        )
        assertEquals(
            "Отключено",
            sessionCardStatusText(ConnState.Ready, publicIp = null),
        )
        assertEquals(
            "Ошибка: нет сети",
            sessionCardStatusText(ConnState.Error, publicIp = null, lastError = "нет сети"),
        )
        assertEquals(
            "Пауза",
            sessionCardStatusText(ConnState.PausedTrustedWifi, publicIp = "1.1.1.1"),
        )
    }

    @Test
    fun selectedAndCurrentModes() {
        assertEquals("Авто", selectedModeLabel("auto"))
        assertEquals("Прямой", selectedModeLabel("direct"))
        assertEquals("Обход", selectedModeLabel("bypass"))
        assertEquals("—", currentModeLabel(ConnState.Ready, activePath = VpnPath.Direct))
        assertEquals(
            "Прямой",
            currentModeLabel(ConnState.Connected, activePath = VpnPath.Direct),
        )
        assertEquals(
            "Обход",
            currentModeLabel(ConnState.Connecting, activePath = VpnPath.Bypass),
        )
    }
}
