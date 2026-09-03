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
            "Подключено",
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
        assertEquals("Прямой", selectedModeLabel("awg"))
        assertEquals("Обход", selectedModeLabel("wdtt"))
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

    @Test
    fun userModePrimaryReflectsStateAndPath() {
        assertEquals(
            "Прямое подключение",
            userModeStatusPrimary(ConnState.Connected, VpnPath.Direct),
        )
        assertEquals(
            "Обход",
            userModeStatusPrimary(ConnState.Connected, VpnPath.Bypass),
        )
        assertEquals(
            "Подключено",
            userModeStatusPrimary(ConnState.Connected, activePath = null),
        )
        assertEquals(
            "Пауза",
            userModeStatusPrimary(ConnState.PausedTrustedWifi, VpnPath.Direct),
        )
        assertEquals(
            "Отключено",
            userModeStatusPrimary(ConnState.Ready, activePath = null),
        )
    }

    @Test
    fun userModeDetailsForConnectedAndPaused() {
        assertEquals(
            "Инкогнито",
            userModeStatusDetails(
                ConnState.Connected,
                softInfo = null,
                lastError = null,
                activePath = VpnPath.Direct,
                hideIp = true,
            ),
        )
        assertEquals(
            null,
            userModeStatusDetails(
                ConnState.Connected,
                softInfo = null,
                lastError = null,
                activePath = VpnPath.Bypass,
                hideIp = false,
            ),
        )
        assertEquals(
            "Прямое · При выходе из сети подключение восстановится.",
            userModeStatusDetails(
                ConnState.PausedTrustedWifi,
                softInfo = "При выходе из сети подключение восстановится.",
                lastError = null,
                activePath = VpnPath.Direct,
            ),
        )
        assertEquals(
            "Доверенная сеть Wi‑Fi · Обход",
            userModeStatusDetails(
                ConnState.PausedTrustedWifi,
                softInfo = null,
                lastError = null,
                activePath = VpnPath.Bypass,
            ),
        )
    }
}
