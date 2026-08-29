package com.nonamevpn.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
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

/**
 * Dual-SIM: prefer the carrier of the data SIM actually in use, not the
 * default [TelephonyManager] (which stays on SIM 1 after a data switch).
 */
fun pickOperatorName(
    carrierName: String?,
    networkOperatorName: String?,
    simOperatorName: String? = null,
    displayName: String? = null,
): String? {
    fun useful(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t.equals("null", ignoreCase = true)) return null
        if (t.matches(Regex("""(?i)sim\s*\d+"""))) return null
        return t
    }
    return useful(carrierName)
        ?: useful(networkOperatorName)
        ?: useful(simOperatorName)
        ?: useful(displayName)
}

data class CellularOperatorInfo(
    val connected: Boolean,
    val operator: String? = null,
    val generation: String? = null,
    val subscriptionId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
)

fun readUnderlayAccessLabel(context: Context): String {
    val app = context.applicationContext
    val wifi = readConnectedWifiState(app, requireBackground = false)
    val cellular = readCellularOperatorInfo(app)
    return formatUnderlayAccessLabel(
        wifiConnected = wifi.connected,
        wifiSsid = wifi.ssid.takeIf { wifi.ssidAvailable },
        cellularConnected = cellular.connected,
        operatorName = cellular.operator,
        generation = cellular.generation,
    )
}

fun readCellularOperatorInfo(context: Context): CellularOperatorInfo {
    val app = context.applicationContext
    if (!hasCellularUnderlay(app)) {
        return CellularOperatorInfo(connected = false)
    }
    val defaultTm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        ?: return CellularOperatorInfo(connected = true)
    val subId = activeCellularSubscriptionId(app)
    val tm = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        runCatching { defaultTm.createForSubscriptionId(subId) }.getOrDefault(defaultTm)
    } else {
        defaultTm
    }
    val operator = pickOperatorName(
        carrierName = subscriptionCarrierName(app, subId),
        networkOperatorName = tm.networkOperatorName,
        simOperatorName = tm.simOperatorName,
        displayName = subscriptionDisplayName(app, subId),
    )
    val generation = runCatching {
        val granted = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@runCatching null
        @Suppress("MissingPermission")
        cellularGenerationLabel(tm.dataNetworkType)
    }.getOrNull()
    return CellularOperatorInfo(
        connected = true,
        operator = operator,
        generation = generation,
        subscriptionId = subId,
    )
}

internal fun hasCellularUnderlay(context: Context): Boolean {
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

internal fun activeCellularSubscriptionId(context: Context): Int {
    context.getSystemService(SubscriptionManager::class.java)
        ?: return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val active = runCatching { SubscriptionManager.getActiveDataSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (active != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return active
    }
    return runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }
        .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
}

@Suppress("MissingPermission")
private fun subscriptionInfo(context: Context, subId: Int) =
    runCatching {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return@runCatching null
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@runCatching null
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return@runCatching null
        sm.getActiveSubscriptionInfo(subId)
    }.getOrNull()

private fun subscriptionCarrierName(context: Context, subId: Int): String? =
    subscriptionInfo(context, subId)?.carrierName?.toString()

private fun subscriptionDisplayName(context: Context, subId: Int): String? =
    subscriptionInfo(context, subId)?.displayName?.toString()
