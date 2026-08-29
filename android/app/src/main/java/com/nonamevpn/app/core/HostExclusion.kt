package com.nonamevpn.app.core

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Site exceptions on Android VpnService are destination routes, not hostnames.
 * Browsers resolve DNS themselves (often via tunnel DNS / DoH), so we:
 *  - keep www/apex aliases,
 *  - exclude both IPv4 /32 and IPv6 /128,
 *  - union every resolver the caller can reach (underlay, VPN, system).
 */
object HostExclusion {
    data class IpRoute(val address: InetAddress, val prefixLength: Int) {
        val key: String
            get() = "${numericHost(address)}/$prefixLength"
    }

    fun normalize(value: String): String {
        var h = value.trim().lowercase()
        if (h.startsWith("http://")) h = h.removePrefix("http://")
        if (h.startsWith("https://")) h = h.removePrefix("https://")
        if (h.startsWith("[")) {
            val end = h.indexOf(']')
            if (end > 1) return h.substring(1, end)
        }
        h = h.substringBefore('/')
        val firstColon = h.indexOf(':')
        val lastColon = h.lastIndexOf(':')
        if (
            firstColon > 0 &&
            firstColon == lastColon &&
            h.substring(firstColon + 1).all { it.isDigit() }
        ) {
            h = h.substring(0, firstColon)
        }
        return h.trim().trim('.')
    }

    fun expandNames(host: String): Set<String> {
        val clean = normalize(host)
        if (clean.isBlank()) return emptySet()
        if (parseLiteral(clean) != null) return setOf(clean)
        val names = linkedSetOf(clean)
        if (clean.startsWith("www.")) {
            val apex = clean.removePrefix("www.")
            if (apex.contains('.')) names.add(apex)
        } else if (clean.count { it == '.' } == 1) {
            names.add("www.$clean")
        }
        return names
    }

    fun parseLiteral(host: String): InetAddress? {
        val clean = normalize(host)
        if (clean.isBlank()) return null
        return runCatching {
            if (looksLikeLiteralIp(clean)) InetAddress.getByName(clean) else null
        }.getOrNull()
    }

    fun routesFor(
        hosts: Set<String>,
        resolve: (String) -> List<InetAddress>,
    ): List<IpRoute> {
        val out = LinkedHashMap<String, IpRoute>()
        for (raw in hosts) {
            for (name in expandNames(raw)) {
                val literal = parseLiteral(name)
                val addrs = if (literal != null) listOf(literal) else resolve(name)
                for (addr in addrs) {
                    if (!isExcludable(addr)) continue
                    val route = IpRoute(addr, prefixLength(addr))
                    out[route.key] = route
                }
            }
        }
        return out.values.toList()
    }

    /**
     * VpnService `excludeRoute` of IPv6 /128 enables IPv6 on the TUN.
     * Happy Eyeballs then black-holes HTTPS while IPv4 keepalives still look
     * “connected”. 0.5.83 only excluded IPv4 /32.
     */
    fun ipv4RoutesFor(
        hosts: Set<String>,
        resolve: (String) -> List<InetAddress>,
    ): List<IpRoute> = routesFor(hosts, resolve).filter { it.prefixLength == 32 }

    fun isExcludable(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isMulticastAddress &&
            !address.isLinkLocalAddress

    fun prefixLength(address: InetAddress): Int = when (address) {
        is Inet6Address -> 128
        else -> 32
    }

    fun numericHost(address: InetAddress): String {
        val raw = address.hostAddress ?: return address.toString()
        return raw.substringBefore('%')
    }

    private fun looksLikeLiteralIp(value: String): Boolean {
        if (value.contains(':')) {
            return value.any { it.isDigit() }
        }
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
