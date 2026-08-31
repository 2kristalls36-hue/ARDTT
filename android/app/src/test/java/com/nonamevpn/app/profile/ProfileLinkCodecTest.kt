package com.nonamevpn.app.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLinkCodecTest {
    @Test
    fun roundTripEncryptedLink() {
        val profile = VpnProfileJson.parse(
            """
            {
              "name": "client-test",
              "deviceId": "dev-link-test",
              "hostId": 3,
              "direct": {
                "endpoint": "159.194.225.162:51820",
                "privateKey": "priv",
                "peerPublicKey": "pub",
                "address": "10.8.0.3/32",
                "dns": ["10.8.0.1"],
                "mtu": 1280,
                "awg": {"Jc": "4"}
              },
              "bypass": {
                "peer": "159.194.225.162:56003",
                "address": "10.9.0.3/32",
                "password": "secret",
                "workers": 3,
                "transport": "tcp",
                "mode": "raw",
                "dial": "auto"
              }
            }
            """.trimIndent(),
        )
        val link = ProfileLinkCodec.buildLink(profile)
        assert(link.startsWith("ardtt://config?"))
        val restored = ProfileLinkCodec.parseLink(link)
        assertEquals(profile.name, restored.name)
        assertEquals(profile.deviceId, restored.deviceId)
        assertEquals(profile.direct.endpoint, restored.direct.endpoint)
        assertEquals(profile.bypass.password, restored.bypass.password)
        assertTrue(link.length < 1600)
    }
}
