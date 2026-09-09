package com.ardtt.app.ui

import android.content.Context
import android.content.Intent
import com.ardtt.app.bypass.VkLoginActivity
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.ConnectionUiAction

fun connectionUiActionLabel(action: ConnectionUiAction): String = when (action) {
    ConnectionUiAction.OpenNetworkSettings -> "Настройки сети"
    ConnectionUiAction.CancelWait -> "Отменить ожидание"
    ConnectionUiAction.Disconnect -> "Отключить"
    ConnectionUiAction.RetryProbe -> "Повторить проверку"
    ConnectionUiAction.RetryNow -> "Повторить сейчас"
    ConnectionUiAction.OpenCaptivePortal -> "Войти в сеть"
    ConnectionUiAction.OpenDiagnostics -> "Диагностика"
    ConnectionUiAction.SignIn -> "Войти"
    ConnectionUiAction.PassCaptcha -> "Пройти проверку"
    ConnectionUiAction.OpenProfile -> "Открыть профиль"
}

fun performConnectionUiAction(
    context: Context,
    conn: ConnectionManager,
    action: ConnectionUiAction,
) {
    when (action) {
        ConnectionUiAction.OpenNetworkSettings -> conn.openNetworkSettings()
        ConnectionUiAction.CancelWait,
        ConnectionUiAction.Disconnect,
        -> conn.disconnect()
        ConnectionUiAction.RetryProbe,
        ConnectionUiAction.RetryNow,
        -> conn.retryNow()
        ConnectionUiAction.OpenCaptivePortal -> conn.openCaptivePortal()
        ConnectionUiAction.OpenDiagnostics -> PendingUiAction.requestOpenServers()
        ConnectionUiAction.SignIn,
        ConnectionUiAction.PassCaptcha,
        -> context.startActivity(
            Intent(context, VkLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        ConnectionUiAction.OpenProfile -> PendingUiAction.requestOpenProfiles()
    }
}
