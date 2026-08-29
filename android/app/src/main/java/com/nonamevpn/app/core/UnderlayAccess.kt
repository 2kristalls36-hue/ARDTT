package com.nonamevpn.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Human-readable underlay: Wi‑Fi SSID or mobile operator, for the tunnel stats panel.
 */
fun formatUnderlayAccessLabel(
    wifiConnected: Boolean,
    wifiSsid: String?,
    cellularConnected: Boolean,
    operatorName: String?,
    generation: String?,
): String {
    if (wifiConnected) {
        val ssid = wifiSsid?.trim().orEmpty()
        return if (ssid.isNotEmpty()) "Wi‑Fi · $ssid" else "Wi‑Fi"
    }
    if (cellularConnected) {
        val op = operatorName?.trim().orEmpty()
        val gen = generation?.trim().orEmpty()
        return when {
            op.isNotEmpty() && gen.isNotEmpty() -> "$op · $gen"
            op.isNotEmpty() -> op
            else -> "Мобильная сеть"
        }
    }
    return "Нет сети"
}

fun cellularGenerationLabel(type: Int): String? = when (type) {
    TelephonyManager.NETWORK_TYPE_GPRS,
    TelephonyManager.NETWORK_TYPE_EDGE,
    TelephonyManager.NETWORK_TYPE_CDMA,
    TelephonyManager.NETWORK_TYPE_1xRTT,
    TelephonyManager.NETWORK_TYPE_IDEN,
    -> "2G"
    TelephonyManager.NETWORK_TYPE_UMTS,
    TelephonyManager.NETWORK_TYPE_EVDO_0,
    TelephonyManager.NETWORK_TYPE_EVDO_A,
    TelephonyManager.NETWORK_TYPE_HSDPA,
    TelephonyManager.NETWORK_TYPE_HSUPA,
    TelephonyManager.NETWORK_TYPE_HSPA,
    TelephonyManager.NETWORK_TYPE_EVDO_B,
    TelephonyManager.NETWORK_TYPE_EHRPD,
    TelephonyManager.NETWORK_TYPE_HSPAP,
    -> "3G"
    TelephonyManager.NETWORK_TYPE_LTE,
    TelephonyManager.NETWORK_TYPE_IWLAN,
    -> "4G"
    TelephonyManager.NETWORK_TYPE_NR -> "5G"
    else -> null
}

fun readUnderlayAccessLabel(context: Context): String {
    val app = context.applicationContext
    val wifi = readConnectedWifiState(app, requireBackground = false)
    val cellular = hasCellularUnderlay(app)
    val (operator, generation) = if (cellular) {
        readCellularOperator(app)
    } else {
        null to null
    }
    return formatUnderlayAccessLabel(
        wifiConnected = wifi.connected,
        wifiSsid = wifi.ssid.takeIf { wifi.ssidAvailable },
        cellularConnected = cellular,
        operatorName = operator,
        generation = generation,
    )
}

private fun hasCellularUnderlay(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    return runCatching {
        cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }.getOrDefault(false)
}

    @Suppress("MissingPermission")
    private fun readCellularOperator(context: Context): Pair<String?, String?> {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        ?: return null to null
    val name = tm.networkOperatorName?.trim().orEmpty()
        .ifBlank { tm.simOperatorName?.trim().orEmpty() }
        .ifBlank { null }
    val gen = runCatching {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@runCatching null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cellularGenerationLabel(tm.dataNetworkType)
        } else {
            @Suppress("DEPRECATION")
            cellularGenerationLabel(tm.networkType)
        }
    }.getOrNull()
    return name to gen
}
