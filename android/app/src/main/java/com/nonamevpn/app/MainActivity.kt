package com.nonamevpn.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.PendingProfileImport
import com.nonamevpn.app.profile.ProfileLinkCodec
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.telemetry.TelemetryRecorder
import com.nonamevpn.app.ui.AppRoot
import com.nonamevpn.app.ui.telemetry.RecordingBorderOverlay
import com.nonamevpn.app.ui.PendingUiAction
import com.nonamevpn.app.ui.theme.ArdttTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppShortcuts.refreshAsync(this)
        handleIncomingIntent(intent)
        val settings = AppSettingsRepository(applicationContext)
        val profiles = ProfileRepository(applicationContext)
        val servers = ServersRepository.get(applicationContext)
        val deploy = DeployEngine.get(applicationContext)
        setContent {
            val recorder = androidx.compose.runtime.remember { TelemetryRecorder.get(applicationContext) }
            val isRecording by recorder.isRecording.collectAsStateWithLifecycle()
            val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
            val palette by settings.themePaletteFlow.collectAsStateWithLifecycle(initialValue = "espresso")
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                ArdttTheme(
                    themeMode = themeMode,
                    palette = palette,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = androidx.compose.ui.graphics.Color.Transparent,
                    ) {
                        AppRoot(
                            settings = settings,
                            profiles = profiles,
                            serversRepo = servers,
                            deployEngine = deploy,
                        )
                    }
                }
                RecordingBorderOverlay(isRecording = isRecording)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_CALL_HASH -> PendingUiAction.requestCallHashSettings()
            com.nonamevpn.app.deploy.DeployService.ACTION_OPEN -> {
                val id = intent.getStringExtra(com.nonamevpn.app.deploy.DeployService.EXTRA_SERVER_ID)
                if (!id.isNullOrBlank()) PendingUiAction.requestOpenDeploy(id)
            }
            AppShortcuts.ACTION_START_TUNNEL -> startTunnelFromShortcut()
            AppShortcuts.ACTION_STOP_TUNNEL -> {
                ConnectionManager.get(applicationContext).disconnect()
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.dataString?.trim().orEmpty()
                if (ProfileLinkCodec.looksLikeLink(uri)) {
                    PendingProfileImport.link = uri
                    Toast.makeText(
                        this,
                        "Ссылка профиля — откройте вкладку «Профили»",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun startTunnelFromShortcut() {
        val settings = AppSettingsRepository(applicationContext)
        lifecycleScope.launch {
            if (!settings.alphaUnlockedSnapshot()) return@launch
            startTunnelFromShortcutUnlocked()
        }
    }

    private fun startTunnelFromShortcutUnlocked() {
        val conn = ConnectionManager.get(applicationContext)
        val state = conn.ui.value.state
        if (
            state == ConnState.Connected ||
            state == ConnState.Connecting ||
            state == ConnState.PausedTrustedWifi
        ) {
            return
        }
        val prep = runCatching { VpnService.prepare(this) }.getOrNull()
        if (prep != null) {
            Toast.makeText(
                this,
                "Разрешите ARDTT создать туннель",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(Intent(this, VpnPermissionActivity::class.java))
        } else {
            conn.connect()
        }
    }

    companion object {
        const val ACTION_OPEN_CALL_HASH = "com.nonamevpn.app.OPEN_CALL_HASH"
    }
}
