package com.ardtt.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

private const val UNKNOWN_WIFI_SSID = "<unknown ssid>"
private const val BACKGROUND_LOCATION_PERMISSION = "android.permission.ACCESS_BACKGROUND_LOCATION"

fun wifiRadioEnabled(context: Context): Boolean =
    runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    }.getOrDefault(false)

enum class TrustedWifiAccessProblem {
    ForegroundPermission,
    BackgroundPermission,
    LocationDisabled,
}

data class ConnectedWifiState(
    val connected: Boolean,
    val ssid: String = "",
    val accessProblem: TrustedWifiAccessProblem? = null,
) {
    val ssidAvailable: Boolean get() = connected && ssid.isNotBlank() && accessProblem == null
}

enum class TrustedWifiTransition {
    None,
    EnterWaiting,
    ResumeVpn,
}

/**
 * Gate between trusted-SSID pause and Auto handover (77.88.8.8 / VPS IP).
 * Unknown SSID must not be treated as open Wi‑Fi.
 */
enum class TrustedWifiHandoverGate {
    /** Feature off, no Wi‑Fi, or SSID known and not in the list — run Auto probe. */
    Proceed,
    /** SSID known and trusted — pause VPN, skip probe. */
    PauseVpn,
    /** Wi‑Fi is up but SSID not readable yet — retry, do not probe. */
    WaitForSsid,
    /**
     * Gave up reading SSID. Keep the current path (soft-restart underlay only).
     * Never Bypass→Direct: this Wi‑Fi might be the home network.
     */
    HoldPath,
}

/** How long to wait for SSID APIs after Wi‑Fi appears before giving up. */
const val TRUSTED_WIFI_SSID_WAIT_MS = 8_000L
const val TRUSTED_WIFI_SSID_RETRY_MS = 1_000L

fun decideTrustedWifiHandoverGate(
    trustedEnabled: Boolean,
    trustedSsids: Set<String>,
    wifi: ConnectedWifiState,
    waitedMs: Long,
    maxWaitMs: Long = TRUSTED_WIFI_SSID_WAIT_MS,
): TrustedWifiHandoverGate {
    if (!trustedEnabled || trustedSsids.isEmpty()) return TrustedWifiHandoverGate.Proceed
    if (!wifi.connected) return TrustedWifiHandoverGate.Proceed
    if (wifi.ssidAvailable) {
        return if (isTrustedSsid(wifi.ssid, trustedSsids)) {
            TrustedWifiHandoverGate.PauseVpn
        } else {
            TrustedWifiHandoverGate.Proceed
        }
    }
    // Permission/location missing will not clear itself in 8s — don't wait, don't probe Direct.
    if (wifi.accessProblem != null) return TrustedWifiHandoverGate.HoldPath
    return if (waitedMs < maxWaitMs) {
        TrustedWifiHandoverGate.WaitForSsid
    } else {
        TrustedWifiHandoverGate.HoldPath
    }
}

fun decideTrustedWifiTransition(
    enabled: Boolean,
    tunnelRunning: Boolean,
    waiting: Boolean,
    wifi: ConnectedWifiState,
    trustedSsids: Set<String>,
): TrustedWifiTransition {
    // Turning the feature off (or clearing the list) while paused must resume VPN.
    if (!enabled) {
        return if (waiting) TrustedWifiTransition.ResumeVpn else TrustedWifiTransition.None
    }
    if (trustedSsids.isEmpty()) {
        return if (waiting) TrustedWifiTransition.ResumeVpn else TrustedWifiTransition.None
    }
    if (waiting) {
        if (!wifi.connected) return TrustedWifiTransition.ResumeVpn
        if (!wifi.ssidAvailable) {
            // SSID APIs often flicker while VPN is paused; stay waiting if Wi‑Fi is still up.
            return TrustedWifiTransition.None
        }
        return if (isTrustedSsid(wifi.ssid, trustedSsids)) {
            TrustedWifiTransition.None
        } else {
            TrustedWifiTransition.ResumeVpn
        }
    }
    if (!tunnelRunning || !wifi.ssidAvailable) return TrustedWifiTransition.None
    return if (isTrustedSsid(wifi.ssid, trustedSsids)) {
        TrustedWifiTransition.EnterWaiting
    } else {
        TrustedWifiTransition.None
    }
}

/** Case-insensitive match — some OEMs alter SSID casing across APIs. */
fun isTrustedSsid(ssid: String, trustedSsids: Set<String>): Boolean {
    if (ssid.isBlank()) return false
    return trustedSsids.any { it.equals(ssid, ignoreCase = true) }
}

fun sanitizeTrustedWifiSsid(value: String): String {
    val clean = value
        .filterNot { Character.isISOControl(it) }
        .trim()
        .removeSurrounding("\"")
        .trim()
    if (clean.isEmpty()) return ""
    val result = StringBuilder()
    var byteCount = 0
    clean.forEach { character ->
        val characterBytes = character.toString().toByteArray(Charsets.UTF_8).size
        if (byteCount + characterBytes <= 32) {
            result.append(character)
            byteCount += characterBytes
        }
    }
    return result.toString()
}

fun hasNearbyWifiDevicesPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == PackageManager.PERMISSION_GRANTED

fun hasTrustedWifiLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

fun hasTrustedWifiForegroundPermission(context: Context): Boolean =
    hasTrustedWifiLocationPermission(context)

fun hasTrustedWifiBackgroundPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, BACKGROUND_LOCATION_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

enum class TrustedWifiPermissionAsk {
    None,
    Location,
    Background,
}

/** Next system dialog to show when enabling or adding a trusted Wi‑Fi. */
fun nextTrustedWifiPermissionAsk(
    hasLocation: Boolean,
    hasBackground: Boolean,
    sdkInt: Int,
    wantBackground: Boolean,
): TrustedWifiPermissionAsk {
    if (!hasLocation) return TrustedWifiPermissionAsk.Location
    if (wantBackground && sdkInt >= Build.VERSION_CODES.Q && !hasBackground) {
        return TrustedWifiPermissionAsk.Background
    }
    return TrustedWifiPermissionAsk.None
}

fun trustedWifiAccessProblem(
    context: Context,
    requireBackground: Boolean = true,
): TrustedWifiAccessProblem? {
    if (!hasTrustedWifiLocationPermission(context)) {
        return TrustedWifiAccessProblem.ForegroundPermission
    }
    if (requireBackground && !hasTrustedWifiBackgroundPermission(context)) {
        return TrustedWifiAccessProblem.BackgroundPermission
    }
    val locationManager = context.getSystemService(LocationManager::class.java)
    if (locationManager?.isLocationEnabled != true) {
        return TrustedWifiAccessProblem.LocationDisabled
    }
    return null
}

@Suppress("DEPRECATION")
fun readConnectedWifiState(
    context: Context,
    requireBackground: Boolean = true,
): ConnectedWifiState {
    val appContext = context.applicationContext
    if (!wifiRadioEnabled(appContext)) {
        return ConnectedWifiState(connected = false)
    }
    val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    val wifiConnected = runCatching {
        connectivityManager?.allNetworks?.any { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        } == true
    }.getOrDefault(false)
    if (!wifiConnected) return ConnectedWifiState(connected = false)

    val accessProblem = trustedWifiAccessProblem(appContext, requireBackground = requireBackground)
    if (accessProblem != null) {
        return ConnectedWifiState(connected = true, accessProblem = accessProblem)
    }

    val capabilitiesSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            connectivityManager?.allNetworks
                ?.asSequence()
                ?.mapNotNull { network -> connectivityManager.getNetworkCapabilities(network) }
                ?.firstOrNull {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                }
                ?.transportInfo
                ?.let { it as? WifiInfo }
                ?.ssid
        }.getOrNull()
    } else {
        null
    }
    val fallbackSsid = runCatching {
        appContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
    }.getOrNull()
    val ssid = sanitizeTrustedWifiSsid(
        listOf(capabilitiesSsid, fallbackSsid)
            .firstOrNull { !it.isNullOrBlank() && !it.equals(UNKNOWN_WIFI_SSID, ignoreCase = true) }
            .orEmpty(),
    )
    return ConnectedWifiState(connected = true, ssid = ssid)
}
