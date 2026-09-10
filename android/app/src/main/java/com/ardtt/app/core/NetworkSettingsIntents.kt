package com.ardtt.app.core

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

fun internetConnectivitySettingsIntent(): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
    } else {
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

fun captivePortalLoginIntent(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("http://connectivitycheck.gstatic.com/generate_204"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
