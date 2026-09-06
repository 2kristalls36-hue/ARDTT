package com.ardtt.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.vpnPermissionDeniedHint
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.unlock.DeviceUnlockCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Widget tap target. A broadcast cannot start the VPN consent UI or wait
 * for a profile after a cold start; this activity can.
 */
class WidgetToggleActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            runCatching { toggle() }
                .onFailure {
                    Toast.makeText(
                        applicationContext,
                        it.message ?: "Не удалось переключить туннель",
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun toggle() {
        val app = applicationContext
        val settings = AppSettingsRepository(app)
        if (!settings.alphaUnlockedSnapshot()) {
            Toast.makeText(app, DeviceUnlockCopy.CONNECT_BLOCKED, Toast.LENGTH_LONG).show()
            openApp()
            finish()
            return
        }
        val catalog = ProfileRepository(app).snapshot()
        val profile = catalog.active
        if (profile == null) {
            Toast.makeText(app, "Нет активного профиля", Toast.LENGTH_LONG).show()
            openApp()
            finish()
            return
        }
        val conn = ConnectionManager.get(app)
        conn.updateProfile(profile)
        conn.setPathMode(ConnPathMode.fromSetting(settings.pathModeName.first()))
        if (widgetTunnelIsRunning(conn.ui.value.state)) {
            conn.disconnect()
            finish()
            return
        }
        val prep = runCatching { VpnService.prepare(this) }.getOrNull()
        if (prep != null) {
            startActivityForResult(prep, REQ)
            return
        }
        conn.connectWhenReady()
        TunnelWidgetProvider.pushFromConnection(app)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK) {
                ConnectionManager.get(applicationContext).connectWhenReady()
            } else {
                Toast.makeText(this, vpnPermissionDeniedHint(this), Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        private const val REQ = 42
    }
}

internal fun widgetTunnelIsRunning(state: ConnState): Boolean =
    state == ConnState.Connected ||
        state == ConnState.Connecting ||
        state == ConnState.PausedTrustedWifi
