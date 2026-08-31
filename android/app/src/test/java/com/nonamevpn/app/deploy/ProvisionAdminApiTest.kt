package com.nonamevpn.app.deploy

import org.junit.Assert.assertEquals
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
}
