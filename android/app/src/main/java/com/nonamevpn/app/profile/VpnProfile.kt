package com.nonamevpn.app.profile

import org.json.JSONArray
import org.json.JSONObject

/**
 * Client profile matching server/provision `Profile` JSON.
 * Call hash is stored separately on-device (never required in this blob).
 */
data class VpnProfile(
    val name: String,
    val deviceId: String,
    val hostId: Int,
    val prefer: String = "direct",
    val hideIp: Boolean = false,
    val expiresAt: Long = 0L,
    val deactivated: Boolean = false,
    val maxDevices: Int = 1,
    val direct: DirectConfig,
    val bypass: BypassConfig,
) {
    val provisionBaseUrl: String?
        get() {
            val host = NetworkEndpoint.hostOf(direct.endpoint) ?: NetworkEndpoint.hostOf(bypass.peer)
            return host?.let { "http://$it:9100" }
        }

    val subscriptionActive: Boolean
        get() {
            if (deactivated) return false
            if (expiresAt <= 0L) return true
            return expiresAt * 1000L > System.currentTimeMillis()
        }
}

data class DirectConfig(
    val endpoint: String,
    val privateKey: String,
    val peerPublicKey: String,
    val address: String,
    val dns: List<String>,
    val mtu: Int,
    val awg: Map<String, String>,
)

data class BypassConfig(
    val peer: String,
    val address: String,
    val password: String,
    val workers: Int,
    val transport: String,
    val mode: String,
    val dial: String,
)

object NetworkEndpoint {
    fun hostOf(endpoint: String?): String? {
        if (endpoint.isNullOrBlank()) return null
        val trimmed = endpoint.trim()
        // host:port or [ipv6]:port — keep simple for v1
        val idx = trimmed.lastIndexOf(':')
        if (idx <= 0) return trimmed
        return trimmed.substring(0, idx).trim().removePrefix("[").removeSuffix("]")
    }
}

object VpnProfileJson {
    fun parse(raw: String): VpnProfile {
        val o = JSONObject(raw)
        val direct = o.getJSONObject("direct")
        val bypass = o.getJSONObject("bypass")
        val dnsArr = direct.optJSONArray("dns") ?: JSONArray()
        val dns = buildList {
            for (i in 0 until dnsArr.length()) add(dnsArr.getString(i))
        }
        val awgObj = direct.optJSONObject("awg")
        val awg = linkedMapOf<String, String>()
        if (awgObj != null) {
            val keys = awgObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                awg[k] = awgObj.get(k).toString()
            }
        }
        return VpnProfile(
            name = o.getString("name"),
            deviceId = o.optString("deviceId", ""),
            hostId = o.optInt("hostId", 0),
            prefer = o.optString("prefer", "direct"),
            hideIp = o.optBoolean("hideIp", false),
            expiresAt = o.optLong("expiresAt", 0L),
            deactivated = o.optBoolean("deactivated", false),
            maxDevices = o.optInt("maxDevices", 1).coerceAtLeast(1),
            direct = DirectConfig(
                endpoint = direct.optString("endpoint", ""),
                privateKey = direct.optString("privateKey", ""),
                peerPublicKey = direct.optString("peerPublicKey", ""),
                address = direct.optString("address", ""),
                dns = dns.ifEmpty { listOf("10.8.0.1") },
                mtu = direct.optInt("mtu", 1280),
                awg = awg,
            ),
            bypass = BypassConfig(
                peer = bypass.optString("peer", ""),
                address = bypass.optString("address", ""),
                password = bypass.optString("password", ""),
                workers = bypass.optInt("workers", 3),
                transport = bypass.optString("transport", "tcp"),
                mode = bypass.optString("mode", "raw"),
                dial = bypass.optString("dial", "auto"),
            ),
        )
    }

    fun encode(profile: VpnProfile): String {
        val dns = JSONArray()
        profile.direct.dns.forEach { dns.put(it) }
        val awg = JSONObject()
        profile.direct.awg.forEach { (k, v) -> awg.put(k, v) }
        return JSONObject()
            .put("name", profile.name)
            .put("deviceId", profile.deviceId)
            .put("hostId", profile.hostId)
            .put("prefer", profile.prefer)
            .put("hideIp", profile.hideIp)
            .put("expiresAt", profile.expiresAt)
            .put("deactivated", profile.deactivated)
            .put("maxDevices", profile.maxDevices)
            .put(
                "direct",
                JSONObject()
                    .put("endpoint", profile.direct.endpoint)
                    .put("privateKey", profile.direct.privateKey)
                    .put("peerPublicKey", profile.direct.peerPublicKey)
                    .put("address", profile.direct.address)
                    .put("dns", dns)
                    .put("mtu", profile.direct.mtu)
                    .put("awg", awg),
            )
            .put(
                "bypass",
                JSONObject()
                    .put("peer", profile.bypass.peer)
                    .put("address", profile.bypass.address)
                    .put("password", profile.bypass.password)
                    .put("workers", profile.bypass.workers)
                    .put("transport", profile.bypass.transport)
                    .put("mode", profile.bypass.mode)
                    .put("dial", profile.bypass.dial),
            )
            .toString(2)
    }

    /** Demo profile for UI scaffolding (same shape as provision output). */
    fun demo(publicHost: String = "203.0.113.10"): VpnProfile = parse(
        """
        {
          "name": "demo-home",
          "deviceId": "dev-demo",
          "hostId": 2,
          "prefer": "direct",
          "hideIp": false,
          "direct": {
            "endpoint": "$publicHost:51820",
            "privateKey": "",
            "peerPublicKey": "",
            "address": "10.8.0.2/32",
            "dns": ["10.8.0.1"],
            "mtu": 1280,
            "awg": {"Jc": 4, "Jmin": 40, "Jmax": 70}
          },
          "bypass": {
            "peer": "$publicHost:56003",
            "address": "10.9.0.2/32",
            "password": "demo-password",
            "workers": 3,
            "transport": "tcp",
            "mode": "raw",
            "dial": "auto"
          }
        }
        """.trimIndent(),
    )
}
