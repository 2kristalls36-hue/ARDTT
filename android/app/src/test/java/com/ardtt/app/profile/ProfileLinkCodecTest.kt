package com.ardtt.app.profile

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLinkCodecTest {
    @Test
    fun roundTripEncryptedLink() {
        val profile = sampleProfile()
        val link = ProfileLinkCodec.buildLink(profile)
        assertTrue(link.startsWith("ardtt://config?"))
        val restored = ProfileLinkCodec.parseLink(link)
        assertEquals(profile.name, restored.name)
        assertEquals(profile.deviceId, restored.deviceId)
        assertEquals(profile.direct.endpoint, restored.direct.endpoint)
        assertEquals(profile.bypass.password, restored.bypass.password)
        assertEquals("4", restored.direct.awg["Jc"])
        assertEquals("1-100", restored.direct.awg["H1"])
    }

    @Test
    fun compactLinkIsShorterThanPrettyJsonLink() {
        val profile = sampleProfile()
        val compact = VpnProfileJson.encode(profile, pretty = false)
        val pretty = VpnProfileJson.encode(profile, pretty = true)
        assertTrue(compact.length < pretty.length)
        assertTrue(!compact.contains("\n"))
        val link = ProfileLinkCodec.buildLink(profile)
        assertTrue(
            "share link should stay well under QR version-40 byte capacity, was ${link.length}",
            link.length < 2500,
        )
    }

    @Test
    fun typicalProvisionProfileFitsInQr() {
        val link = ProfileLinkCodec.buildLink(sampleProfile())
        val matrix = QRCodeWriter().encode(
            link,
            BarcodeFormat.QR_CODE,
            512,
            512,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            ),
        )
        assertTrue(matrix.width >= 512)
        assertTrue(matrix.height >= 512)
        var dark = 0
        var light = 0
        // Quiet zone can exceed 16px when the QR is sparse; sample past the margin.
        val origin = 32
        for (y in origin until origin + 16) {
            for (x in origin until origin + 16) {
                if (matrix.get(x, y)) dark++ else light++
            }
        }
        assertTrue("expected mixed modules near finders, dark=$dark light=$light", dark > 0 && light > 0)
    }

    private fun sampleProfile() = VpnProfileJson.parse(
        """
        {
          "name": "client-test",
          "deviceId": "dev-link-test",
          "hostId": 3,
          "direct": {
            "endpoint": "159.194.225.162:51820",
            "privateKey": "5SfYBMJJFK3W+LBgz64IlNf1X9ExXskmKF6ZiFH9d+k=",
            "peerPublicKey": "lnykmHrQlhOT1J1k3FZ1jebM5hC2sTQx36NA+1/JyAw=",
            "address": "10.8.0.3/32",
            "dns": ["10.8.0.1"],
            "mtu": 1280,
            "awg": {
              "Jc": "4",
              "Jmin": "40",
              "Jmax": "70",
              "S1": "0",
              "S2": "0",
              "S3": "0",
              "S4": "0",
              "H1": "1-100",
              "H2": "101-200",
              "H3": "201-300",
              "H4": "301-400"
            }
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
}
