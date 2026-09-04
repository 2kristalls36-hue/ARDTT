package com.ardtt.app.tunnel

import com.ardtt.app.profile.DirectConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgUserspaceConfigTest {
    @Test
    fun buildsUserspaceStringWithHexKeysAndAwgParams() {
        val direct = DirectConfig(
            endpoint = "203.0.113.10:51820",
            privateKey = "5SfYBMJJFK3W+LBgz64IlNf1X9ExXskmKF6ZiFH9d+k=",
            peerPublicKey = "lnykmHrQlhOT1J1k3FZ1jebM5hC2sTQx36NA+1/JyAw=",
            address = "10.8.0.2/32",
            dns = listOf("1.1.1.1"),
            mtu = 1280,
            awg = mapOf(
                "Jc" to "4",
                "Jmin" to "40",
                "Jmax" to "70",
                "H1" to "1-100",
                "H2" to "101-200",
                "S1" to "0",
            ),
        )
        val cfg = AwgUserspaceConfig.build(direct)
        assertTrue(cfg.contains("private_key=e527d804c24914add6f8b060cfae0894d7f55fd1315ec926285e998851fd77e9"))
        assertTrue(cfg.contains("public_key="))
        assertTrue(cfg.contains("endpoint=203.0.113.10:51820"))
        assertTrue(cfg.contains("jc=4"))
        assertTrue(cfg.contains("jmin=40"))
        assertTrue(cfg.contains("jmax=70"))
        assertTrue(cfg.contains("h1=1-100"))
        assertTrue(cfg.contains("h2=101-200"))
        assertTrue(cfg.contains("s1=0"))
        assertTrue(cfg.contains("allowed_ip=0.0.0.0/0"))
        assertTrue(cfg.contains("persistent_keepalive_interval=25"))
    }

    @Test
    fun resolveEndpointKeepsIpLiteral() {
        assertEquals(
            "1.2.3.4:51820",
            AwgUserspaceConfig.resolveEndpoint("1.2.3.4:51820", retries = 1),
        )
    }
}
