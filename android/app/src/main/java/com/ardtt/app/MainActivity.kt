package com.ardtt.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.deploy.DeployEngine
import com.ardtt.app.deploy.PendingServerImport
import com.ardtt.app.deploy.ServerLinkCodec
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.profile.PendingProfileImport
import com.ardtt.app.profile.ProfileLinkCodec
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.telemetry.TelemetryRecorder
import com.ardtt.app.ui.AppRoot
import com.ardtt.app.ui.telemetry.RecordingBorderOverlay
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.theme.ArdttTheme
import kotlinx.coroutines.flow.first
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

            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                ArdttTheme(themeMode = themeMode) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
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
            ACTION_OPEN_UPDATE_SETTINGS -> PendingUiAction.requestOpenUpdateDownload()
            ACTION_OPEN_PROFILES -> PendingUiAction.requestOpenProfiles()
            com.ardtt.app.deploy.DeployService.ACTION_OPEN -> {
                val id = intent.getStringExtra(com.ardtt.app.deploy.DeployService.EXTRA_SERVER_ID)
                if (!id.isNullOrBlank()) PendingUiAction.requestOpenDeploy(id)
            }
            AppShortcuts.ACTION_START_TUNNEL -> startTunnelFromShortcut()
            AppShortcuts.ACTION_STOP_TUNNEL -> {
                ConnectionManager.get(applicationContext).disconnect()
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.dataString?.trim().orEmpty()
                when {
                    ServerLinkCodec.looksLikeLink(uri) -> {
                        PendingServerImport.offer(uri)
                        PendingUiAction.requestOpenServers()
                        Toast.makeText(
                            this,
                            "Ссылка серверов — откройте вкладку «Серверы»",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    ProfileLinkCodec.looksLikeLink(uri) -> {
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
    }

    private fun startTunnelFromShortcut() {
        val settings = AppSettingsRepository(applicationContext)
        lifecycleScope.launch {
            if (!settings.alphaUnlockedSnapshot()) return@launch
            startTunnelFromShortcutUnlocked()
        }
    }

    private fun startTunnelFromShortcutUnlocked() {
        lifecycleScope.launch {
            val app = applicationContext
            val settings = AppSettingsRepository(app)
            val catalog = ProfileRepository(app).snapshot()
            val conn = ConnectionManager.get(app)
            conn.updateProfile(catalog.active)
            conn.setPathMode(ConnPathMode.fromSetting(settings.pathModeName.first()))
            if (widgetTunnelIsRunning(conn.ui.value.state)) return@launch
            val prep = runCatching { VpnService.prepare(this@MainActivity) }.getOrNull()
            if (prep != null) {
                Toast.makeText(
                    this@MainActivity,
                    "Разрешите ARDTT создать туннель",
                    Toast.LENGTH_LONG,
                ).show()
                startActivity(Intent(this@MainActivity, VpnPermissionActivity::class.java))
            } else {
                conn.connectWhenReady()
            }
        }
    }

    companion object {
        const val ACTION_OPEN_CALL_HASH = "com.ardtt.app.OPEN_CALL_HASH"
        const val ACTION_OPEN_UPDATE_SETTINGS = "com.ardtt.app.OPEN_UPDATE_SETTINGS"
        const val ACTION_OPEN_PROFILES = "com.ardtt.app.OPEN_PROFILES"
    }
}
