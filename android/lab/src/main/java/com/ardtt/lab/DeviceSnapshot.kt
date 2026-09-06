package com.ardtt.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager

data class DeviceSnapshot(
    val model: String,
    val sdk: Int,
    val release: String,
    val wifiOn: Boolean,
    val wifiDefault: Boolean,
    val cellular: Boolean,
    val operator: String,
    val vpn: Boolean,
    val ardttInstalled: Boolean,
    val batteryPct: Int?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "model" to model,
        "sdk" to sdk,
        "release" to release,
        "wifi_on" to wifiOn,
        "wifi_is_default" to wifiDefault,
        "cellular" to cellular,
        "operator" to operator,
        "vpn" to vpn,
        "ardtt_installed" to ardttInstalled,
        "battery_pct" to batteryPct,
        "whitelist_hint" to when {
            wifiOn -> "wifi_on_ardtt_auto_uses_direct"
            else -> "wifi_off_ok_for_operator_whitelist"
        },
    )

    companion object {
        fun capture(context: Context, binder: CellularBinder): DeviceSnapshot {
            val app = context.applicationContext
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val wifiSetting = Settings.Global.getInt(app.contentResolver, "wifi_on", 0) == 1
            val active = cm.activeNetwork
            val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
            val wifiDefault = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            var vpn = false
            var cellular = binder.network() != null
            cm.allNetworks.forEach { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@forEach
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular = true
            }
            val operator = listOf(
                tm?.simOperatorName,
                tm?.networkOperatorName,
            ).mapNotNull { it?.trim()?.takeIf { name -> name.isNotEmpty() } }.firstOrNull() ?: ""
            val installed = runCatching {
                app.packageManager.getPackageInfo(LabProtocol.ARDTT_PACKAGE, 0)
                true
            }.getOrDefault(false)
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return DeviceSnapshot(
                model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                sdk = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE ?: "",
                wifiOn = wifiSetting || wifiDefault,
                wifiDefault = wifiDefault,
                cellular = cellular,
                operator = operator,
                vpn = vpn,
                ardttInstalled = installed,
                batteryPct = pct?.takeIf { it in 0..100 },
            )
        }
    }
}

fun launchArdtt(context: Context): String? {
    val launch = context.packageManager.getLaunchIntentForPackage(LabProtocol.ARDTT_PACKAGE)
        ?: return "ARDTT не установлен (${LabProtocol.ARDTT_PACKAGE})"
    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(launch)
        null
    } catch (err: Exception) {
        err.message ?: "не удалось открыть ARDTT"
    }
}

