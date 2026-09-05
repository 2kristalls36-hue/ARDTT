package com.ardtt.app.deploy

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLinkCodecTest {
    @Test
    fun roundTripEncryptedLink() {
        val servers = listOf(sampleServer(), sampleServer(id = "srv-2", name = "exit", host = "10.0.0.2"))
        val link = ServerLinkCodec.buildLink(servers)
        assertTrue(link.startsWith("ardtt://servers?"))
        assertTrue(ServerLinkCodec.looksLikeLink(link))
        assertFalse(ServerLinkCodec.looksLikeLink("ardtt://config?v=1&n=x&p=y"))
        val restored = ServerLinkCodec.parseLink(link)
        assertEquals(2, restored.size)
        assertEquals(servers[0].password, restored[0].password)
        assertEquals(servers[0].privateKeyPem, restored[0].privateKeyPem)
        assertEquals(servers[1].host, restored[1].host)
        assertEquals(servers[1].cascadePassword, restored[1].cascadePassword)
    }

    @Test
    fun typicalExportFitsInQr() {
        val link = ServerLinkCodec.buildLink(listOf(sampleServer()))
        assertTrue(
            "share link should stay well under QR version-40 byte capacity, was ${link.length}",
            link.length < 2500,
        )
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
    }

    @Test
    fun deployTargetJsonRoundTrip() {
        val original = sampleServer(cascade = true)
        val parsed = DeployTargetJson.parse(DeployTargetJson.encode(original))
        assertEquals(original, parsed)
        val list = DeployTargetJson.parseList(DeployTargetJson.encodeList(listOf(original)))
        assertEquals(listOf(original), list)
    }

    private fun sampleServer(
        id: String = "srv-1",
        name: String = "edge",
        host: String = "203.0.113.10",
        cascade: Boolean = false,
    ) = DeployTarget(
        id = id,
        name = name,
        host = host,
        sshPort = 22,
        sshUser = "root",
        password = "s3cret",
        privateKeyPem = "",
        publicHost = host,
        autoPorts = true,
        directPort = 51820,
        bypassPort = 56003,
        cascadeEnabled = cascade,
        cascadeHost = if (cascade) "203.0.113.20" else "",
        cascadePassword = if (cascade) "cascade-pass" else "",
        osId = "ubuntu",
        osVersion = "Ubuntu 24.04",
        lastDeployedAtMs = 1_700_000_000_000L,
    )
}
