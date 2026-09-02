package com.nonamevpn.app.core

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Site exceptions on Android VpnService are destination routes, not hostnames.
 * Browsers resolve DNS themselves (often via tunnel DNS / DoH), so we:
 *  - keep www/apex aliases,
 *  - support wildcard domains (*.example.com) via common subdomain variants,
 *  - support explicit IPv4 CIDR entries (e.g. 203.0.113.0/24),
 *  - union every resolver the caller can reach (underlay, VPN, system).
 */
object HostExclusion {
    private const val WILDCARD_PREFIX = "*."
    private val commonSubdomainPrefixes = listOf(
        "www",
        "m",
        "api",
        "cdn",
        "static",
        "img",
        "assets",
        "mobile",
    )

    data class IpRoute(val address: InetAddress, val prefixLength: Int) {
        val key: String
            get() = "${numericHost(address)}/$prefixLength"
    }

    fun normalize(value: String): String {
        var h = value.trim().lowercase()
        val wildcard = h.startsWith(WILDCARD_PREFIX)
        if (wildcard) h = h.removePrefix(WILDCARD_PREFIX)
        parseIpv4Cidr(h)?.let { cidr ->
            return cidr.key
        }
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
        val normalized = h.trim().trim('.')
        return if (wildcard && normalized.isNotBlank() && parseLiteral(normalized) == null) {
            "$WILDCARD_PREFIX$normalized"
        } else {
            normalized
        }
    }

    fun expandNames(host: String): Set<String> {
        val clean = normalize(host)
        if (clean.isBlank()) return emptySet()
        if (clean.startsWith(WILDCARD_PREFIX)) {
            val root = clean.removePrefix(WILDCARD_PREFIX)
            if (root.isBlank()) return emptySet()
            val names = linkedSetOf(root)
            commonSubdomainPrefixes.forEach { prefix ->
                names.add("$prefix.$root")
            }
            return names
        }
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
            val cidr = parseIpv4Cidr(raw)
            if (cidr != null) {
                out[cidr.key] = cidr
                continue
            }
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

    /** Keep only IPv4 routes; includes literal CIDR and resolved host addresses. */
    fun ipv4RoutesFor(
        hosts: Set<String>,
        resolve: (String) -> List<InetAddress>,
    ): List<IpRoute> = routesFor(hosts, resolve).filter { it.address !is Inet6Address }

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

    private fun parseIpv4Cidr(value: String): IpRoute? {
        val clean = value.trim()
        val slash = clean.indexOf('/')
        if (slash <= 0 || slash == clean.lastIndex) return null
        val ip = clean.substring(0, slash)
        val prefix = clean.substring(slash + 1).toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val addr = parseLiteral(ip) ?: return null
        if (addr is Inet6Address) return null
        return IpRoute(addr, prefix)
    }
}
