package com.ardtt.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Detects whether any VPN transport is currently active at the OS level. */
fun hasActiveVpnTransport(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    return cm.allNetworks.any { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@any false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** User-facing hint for denied/failed VPN bring-up. */
fun vpnPermissionDeniedHint(context: Context): String {
    return if (hasActiveVpnTransport(context)) {
        "Обнаружено другое активное VPN-подключение. Отключите его и повторите попытку запуска ARDTT."
    } else {
        "Не предоставлено системное разрешение на создание VPN-подключения."
    }
}
