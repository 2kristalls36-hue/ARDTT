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
    }
}
