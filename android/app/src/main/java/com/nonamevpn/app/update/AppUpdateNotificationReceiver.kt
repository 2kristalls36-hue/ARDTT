package com.nonamevpn.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppUpdateNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val controller = AppUpdateController.get(context)
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    controller.checkAndWait()
                    controller.download()
                }
            }
            ACTION_CANCEL_DOWNLOAD -> controller.cancel()
            ACTION_INSTALL_UPDATE -> controller.install()
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.nonamevpn.app.action.UPDATE_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.nonamevpn.app.action.UPDATE_CANCEL_DOWNLOAD"
        const val ACTION_INSTALL_UPDATE = "com.nonamevpn.app.action.UPDATE_INSTALL"
    }
}
