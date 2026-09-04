package com.ardtt.app.telemetry.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.ardtt.app.core.readCellularOperatorInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import org.json.JSONObject

object NetworkInfoCollector {
    fun snapshot(context: Context): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }

        return JSONObject()
            .put("wifi", wifiSnapshot(context, caps))
            .put("cellular", cellularSnapshot(context, caps))
            .put("device_ip", localIpv4())
    }

    @Suppress("DEPRECATION")
    private fun wifiSnapshot(context: Context, caps: NetworkCapabilities?): JSONObject {
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val out = JSONObject().put("connected", onWifi)
        if (!onWifi) return out

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val ssid = runCatching {
            val raw = info.ssid?.trim('"')
            if (raw.isNullOrBlank() || raw == "<unknown ssid>") null else raw
        }.getOrNull()

        val canReadSsid = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        out.put("ssid", if (canReadSsid) ssid else JSONObject.NULL)
        out.put("bssid", if (canReadSsid) info.bssid else JSONObject.NULL)
        out.put("rssi_dbm", info.rssi)
        out.put("link_speed_mbps", info.linkSpeed)
        return out
    }

    @Suppress("DEPRECATION")
    private fun cellularSnapshot(context: Context, caps: NetworkCapabilities?): JSONObject {
        val info = readCellularOperatorInfo(context)
        val onCell = info.connected || caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val out = JSONObject().put("connected", onCell)
        if (!onCell) return out

        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) {
            out.put("permission", "READ_PHONE_STATE_denied")
            info.operator?.let { out.put("operator", it) }
            return out
        }

        val defaultTm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val tm = if (info.subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching { defaultTm.createForSubscriptionId(info.subscriptionId) }.getOrDefault(defaultTm)
        } else {
            defaultTm
        }
        out.put("operator", info.operator ?: tm.networkOperatorName)
        out.put("network_type", info.generation ?: networkGeneration(tm.dataNetworkType))
        if (info.subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            out.put("subscription_id", info.subscriptionId)
        }
        out.put("roaming", tm.isNetworkRoaming)

        val signal = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val ci = tm.signalStrength
                JSONObject()
                    .put("dbm", ci?.cellSignalStrengths?.firstOrNull()?.dbm)
                    .put("asu", ci?.cellSignalStrengths?.firstOrNull()?.asuLevel)
            } else {
                JSONObject.NULL
            }
        }.getOrElse { JSONObject.NULL }
        out.put("signal", signal)
        return out
    }

    private fun networkGeneration(type: Int): String = when (type) {
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
        else -> "unknown"
    }

    private fun localIpv4(): String? {
        return NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
