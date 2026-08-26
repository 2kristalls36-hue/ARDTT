package com.nonamevpn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.AppRoot
import com.nonamevpn.app.ui.theme.NonameTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Avoid fully transparent system bars — OEM VPN consent dialogs can render
        // as a "glass" overlay on top of edge-to-edge Compose content.
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        val settings = AppSettingsRepository(applicationContext)
        val profiles = ProfileRepository(applicationContext)
        val servers = ServersRepository(applicationContext)
        val deploy = DeployEngine(applicationContext)
        setContent {
            NonameTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        settings = settings,
                        profiles = profiles,
                        serversRepo = servers,
                        deployEngine = deploy,
                    )
                }
            }
        }
    }
}
