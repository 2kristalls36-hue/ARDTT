package com.nonamevpn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.AppRoot
import com.nonamevpn.app.ui.theme.NonameTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettingsRepository(applicationContext)
        val profiles = ProfileRepository(applicationContext)
        val servers = ServersRepository(applicationContext)
        val deploy = DeployEngine(applicationContext)
        setContent {
            val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
            val palette by settings.themePaletteFlow.collectAsStateWithLifecycle(initialValue = "espresso")
            val dynamic by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
            NonameTheme(
                themeMode = themeMode,
                palette = palette,
                dynamicColor = dynamic,
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
        }
    }
}
