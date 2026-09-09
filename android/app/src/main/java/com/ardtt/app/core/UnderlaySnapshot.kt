package com.ardtt.app.core

/**
 * Identity of a physical underlay. RSSI changes do not create a new key.
 * Unknown SIM is `simId = null` — never substitute SIM 1.
 */
data class NetworkKey(
    val handle: Long,
    val transport: UnderlayKind,
    val simId: Int?,
    val configFingerprint: String,
) {
    val isCellular: Boolean get() = transport == UnderlayKind.Cellular
    val isWifi: Boolean get() = transport == UnderlayKind.Wifi
    val isEthernet: Boolean get() = transport == UnderlayKind.Ethernet
}

enum class UnderlayAvailability {
    /** No INTERNET+NOT_VPN physical network. */
    None,
    /** Callbacks arrived but capabilities are still incomplete. */
    Incomplete,
    /** DATA_SUSPENDED / missing NOT_SUSPENDED on the selected network. */
    Suspended,
    Captive,
    Usable,
}

data class UnderlaySnapshot(
    val key: NetworkKey? = null,
    val kind: UnderlayKind = UnderlayKind.Other,
    val availability: UnderlayAvailability = UnderlayAvailability.None,
    val handle: Long? = null,
    val validated: Boolean = false,
    val notSuspended: Boolean = true,
    val captivePortal: Boolean = false,
    val simId: Int? = null,
    val wifiConnected: Boolean = false,
    val cellularConnected: Boolean = false,
    val ethernetConnected: Boolean = false,
    val capabilitiesComplete: Boolean = true,
    val networkEpoch: Long = 0L,
) {
    val hasPhysicalNetwork: Boolean
        get() = availability != UnderlayAvailability.None &&
            availability != UnderlayAvailability.Incomplete

    val allowsNetworkOps: Boolean
        get() = availability == UnderlayAvailability.Usable ||
            (availability == UnderlayAvailability.Captive && cellularConnected)

    val selectedIsCellular: Boolean
        get() = kind == UnderlayKind.Cellular && hasPhysicalNetwork
}

fun selectedNotSuspended(
    selectedKind: UnderlayKind,
    selectedNotSuspendedCap: Boolean,
    cellularDataSuspended: Boolean,
): Boolean {
    val dataSuspendedApplies = selectedKind == UnderlayKind.Cellular && cellularDataSuspended
    return selectedNotSuspendedCap && !dataSuspendedApplies
}

data class PhysicalNetworkPresence(
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    val ethernet: Boolean = false,
)

fun mergePhysicalPresence(
    selectedWifi: Boolean,
    selectedCellular: Boolean,
    selectedEthernet: Boolean,
    inventoryWifi: Boolean,
    inventoryCellular: Boolean,
    inventoryEthernet: Boolean,
): PhysicalNetworkPresence = PhysicalNetworkPresence(
    wifi = selectedWifi || inventoryWifi,
    cellular = selectedCellular || inventoryCellular,
    ethernet = selectedEthernet || inventoryEthernet,
)

fun networkConfigFingerprint(
    addresses: List<String>,
    dns: List<String>,
    ifName: String?,
): String {
    val addr = addresses.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
    val nameservers = dns.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
    return "${ifName.orEmpty()}|$addr|$nameservers"
}

fun classifyUnderlayAvailability(
    hasInternet: Boolean,
    notVpn: Boolean,
    capabilitiesComplete: Boolean,
    notSuspended: Boolean,
    captivePortal: Boolean,
): UnderlayAvailability = when {
    !hasInternet || !notVpn -> UnderlayAvailability.None
    !capabilitiesComplete -> UnderlayAvailability.Incomplete
    !notSuspended -> UnderlayAvailability.Suspended
    captivePortal -> UnderlayAvailability.Captive
    else -> UnderlayAvailability.Usable
}
