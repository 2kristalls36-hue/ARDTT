package com.ardtt.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.holdsUserSession
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.vpnPermissionDeniedHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Widget tap target. A broadcast cannot start the VPN consent UI or wait
 * for a profile after a cold start; this activity can.
 *
 * It must not join the app task or show a window: otherwise a tap brings
 * MainActivity (or the last recents snapshot) up for a few seconds and
 * [finish] sends the whole task back to the launcher.
 */
class WidgetToggleActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
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

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun toggle() {
        val result = runQuickLaunchToggle(applicationContext) {
            runCatching { VpnService.prepare(this) }.getOrNull()
        }
        when (result.outcome) {
            QuickLaunchOutcome.MissingProfile -> {
                Toast.makeText(applicationContext, "Нет активного профиля", Toast.LENGTH_LONG).show()
                openApp()
                finish()
            }
            QuickLaunchOutcome.NeedVpnConsent -> {
                val consent = result.vpnConsentIntent
                if (consent != null) {
                    startActivityForResult(consent, REQ)
                } else {
                    finish()
                }
            }
            QuickLaunchOutcome.Disconnect,
            QuickLaunchOutcome.ConnectInPlace -> finish()
        }
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

internal fun widgetToggleLaunchFlags(): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_NO_ANIMATION or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

internal fun widgetTunnelIsRunning(state: ConnState): Boolean = state.holdsUserSession()
