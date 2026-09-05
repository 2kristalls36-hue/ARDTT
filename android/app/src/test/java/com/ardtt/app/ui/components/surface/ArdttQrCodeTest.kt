package com.ardtt.app.ui.components.surface

import com.ardtt.app.profile.ProfileLinkCodec
import com.ardtt.app.profile.VpnProfileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttQrCodeTest {
    @Test
    fun matrixRoundTripDecodesPayload() {
        val payload = "ardtt://config?v=1&n=test&p=" + "A".repeat(400)
        val matrix = encodeQrMatrix(payload)
        assertTrue(matrix.width >= 21 + QR_QUIET_MODULES * 2)
        assertEquals(payload, decodeQrFromMatrix(matrix))
    }

    @Test
    fun quietZoneIsWhite() {
        val matrix = encodeQrMatrix("ardtt://config?v=1&n=q")
        for (i in 0 until QR_QUIET_MODULES) {
            assertTrue(!matrix[i, i])
            assertTrue(!matrix[matrix.width - 1 - i, i])
            assertTrue(!matrix[i, matrix.height - 1 - i])
        }
    }

    @Test
    fun typicalEncryptedProfileLinkScansBack() {
        val profile = VpnProfileJson.parse(
            """
            {
              "name": "scan-test",
              "deviceId": "dev-scan",
              "hostId": 2,
              "direct": {
                "endpoint": "159.194.225.162:51820",
                "privateKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "peerPublicKey": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
                "address": "10.8.0.4/32",
                "dns": ["10.8.0.1"],
                "mtu": 1280,
                "awg": {"Jc": "4", "Jmin": "40", "Jmax": "70"}
              },
              "bypass": {
                "peer": "159.194.225.162:56003",
                "address": "10.9.0.4/32",
                "password": "secret-pass",
                "transport": "tcp",
                "mode": "raw",
                "dial": "auto"
              }
            }
            """.trimIndent(),
        )
        val link = ProfileLinkCodec.buildLink(profile)
        assertEquals(link, decodeQrFromMatrix(encodeQrMatrix(link)))
    }
}
