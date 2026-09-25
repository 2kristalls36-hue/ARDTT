package com.ardtt.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import com.ardtt.app.core.vpnPermissionDeniedHint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Transparent screen only for the system VPN consent dialog.
 * Used by the Quick Settings tile and shortcuts — the main UI stays closed.
 * After consent, the tunnel starts from the saved profile (qWDTT).
 */
class VpnPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val prep = runCatching { VpnService.prepare(this) }.getOrNull()
        if (prep != null) {
            startActivityForResult(prep, REQ)
        } else {
            startTunnelAndFinish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK) {
            startTunnelAndFinish()
        } else {
            if (requestCode == REQ) {
                Toast.makeText(this, vpnPermissionDeniedHint(this), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun startTunnelAndFinish() {
        startScope.launch {
            runCatching {
                val result = runQuickLaunchToggle(applicationContext) {
                    runCatching { VpnService.prepare(this@VpnPermissionActivity) }.getOrNull()
                }
                if (result.outcome == QuickLaunchOutcome.MissingProfile) {
                    Toast.makeText(applicationContext, "Нет активного профиля", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                Toast.makeText(
                    applicationContext,
                    it.message ?: "Не удалось переключить туннель",
                    Toast.LENGTH_LONG,
                ).show()
            }
            finish()
        }
    }

    companion object {
        private const val REQ = 42
        private val startScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
