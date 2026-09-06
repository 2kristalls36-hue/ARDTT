package com.ardtt.lab

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.lab.ui.LabScreen
import com.ardtt.lab.ui.LabTheme

class MainActivity : ComponentActivity() {
    private val screenGranted = mutableStateOf(false)

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val screenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        ScreenCaptureStore.accept(applicationContext, result.resultCode, result.data)
        screenGranted.value = ScreenCaptureStore.granted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        screenGranted.value = ScreenCaptureStore.granted()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val settings = LabSettings(this)
        val saved = settings.load()
        setContent {
            val live by LabService.state.collectAsStateWithLifecycle()
            val granted by screenGranted
            val form = if (live.host.isNotBlank() || live.online || live.connecting) {
                live
            } else {
                live.copy(
                    host = saved.host,
                    sshPort = saved.sshPort.toString(),
                    user = saved.user,
                    password = saved.password,
                    remotePort = saved.remotePort.toString(),
                )
            }.copy(screenGranted = live.screenGranted || granted)
            LabTheme {
                LabScreen(
                    state = form,
                    onConnect = { target -> LabService.startConnect(this, target) },
                    onDisconnect = { LabService.stop(this) },
                    onAllowScreen = {
                        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCapture.launch(mgr.createScreenCaptureIntent())
                    },
                )
            }
        }
    }
}
