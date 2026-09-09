package com.ardtt.app.ui

import com.ardtt.app.core.ConnectionUiAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionUiActionsTest {
    @Test
    fun labelsMatchProductCopy() {
        assertEquals("Настройки сети", connectionUiActionLabel(ConnectionUiAction.OpenNetworkSettings))
        assertEquals("Отменить ожидание", connectionUiActionLabel(ConnectionUiAction.CancelWait))
        assertEquals("Повторить сейчас", connectionUiActionLabel(ConnectionUiAction.RetryNow))
        assertEquals("Войти в сеть", connectionUiActionLabel(ConnectionUiAction.OpenCaptivePortal))
        assertEquals("Диагностика", connectionUiActionLabel(ConnectionUiAction.OpenDiagnostics))
    }

    @Test
    fun openDiagnosticsRequestsDiagnosticsTab() {
        PendingUiAction.requestOpenDiagnostics()
        assertEquals(true, PendingUiAction.consumeOpenDiagnostics())
        assertEquals(false, PendingUiAction.consumeOpenDiagnostics())
        assertEquals(AppDestination.Diagnostics.route, ArdttNavPlan.diagnosticsRoute(admin = true))
        assertEquals(AppDestination.Logs.route, ArdttNavPlan.diagnosticsRoute(admin = false))
    }
}
