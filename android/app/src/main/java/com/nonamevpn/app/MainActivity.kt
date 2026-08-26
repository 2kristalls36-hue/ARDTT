package com.nonamevpn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.AppRoot
import com.nonamevpn.app.ui.theme.NonameTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettingsRepository(applicationContext)
        setContent {
            NonameTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(settings = settings)
                }
            }
        }
    }
}
