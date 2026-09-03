package com.nonamevpn.app.deploy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionAdminApiTest {
    @Test
    fun pathSegmentUsesPercent20ForSpaces() {
        val enc = encodePathSegment("Юля тест")
        assertTrue(enc.contains("%20"))
        assertTrue(!enc.contains("+"))
        assertTrue(enc.contains("%D0%AE"))
    }

    @Test
    fun asciiNameUnchanged() {
        assertEquals("client-2808", encodePathSegment("client-2808"))
    }

    @Test
    fun conflictMessageIsHuman() {
        assertEquals(
            "Клиент с таким именем уже есть",
            httpErrorMessage(409, """{"error":"user \"Юля тест\" already exists"}"""),
        )
        assertEquals("Клиент не найден", httpErrorMessage(404, """{"error":"not found"}"""))
    }

    @Test
    fun healthCascadeHostPrefersExplicitFieldThenPeer() {
        val withHost = JSONObject(
            """{"ok":true,"cascade":true,"role":"entry","cascadeHost":"2.26.125.160"}""",
        )
        assertEquals("2.26.125.160", ProvisionAdminApi.cascadeHostFromHealth(withHost))
        val withPeer = JSONObject(
            """{"ok":true,"cascade":true,"role":"entry","cascadePeer":"2.26.125.160:51820"}""",
        )
        assertEquals("2.26.125.160", ProvisionAdminApi.cascadeHostFromHealth(withPeer))
        val live = ProvisionAdminApi.liveCascadeHost(
            ProvisionAdminApi.HealthInfo(
                ok = true,
                cascade = true,
                role = "entry",
                cascadeHost = "2.26.125.160",
            ),
        )
        assertEquals("2.26.125.160", live)
        assertNull(
            ProvisionAdminApi.liveCascadeHost(
                ProvisionAdminApi.HealthInfo(ok = true, cascade = true, role = "exit", cascadeHost = "1.1.1.1"),
            ),
        )
        assertNull(
            ProvisionAdminApi.liveCascadeHost(
                ProvisionAdminApi.HealthInfo(ok = true, cascade = false, role = "entry", cascadeHost = "2.26.125.160"),
            ),
        )
        val flagged = ProvisionAdminApi.liveCascadeInfo(
            ProvisionAdminApi.HealthInfo(ok = true, cascade = true, role = "entry"),
        )
        assertTrue(flagged.enabled)
        assertNull(flagged.host)
    }
}
