package com.nonamevpn.app.core

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostExclusionTest {
    @Test
    fun normalizeStripsSchemePathAndPort() {
        assertEquals("example.com", HostExclusion.normalize("https://Example.COM:443/path"))
        assertEquals("example.com", HostExclusion.normalize("http://example.com/foo"))
        assertEquals("10.1.2.3", HostExclusion.normalize("10.1.2.3:8080"))
    }

    @Test
    fun normalizeKeepsIpv6InBrackets() {
        assertEquals("2001:db8::1", HostExclusion.normalize("https://[2001:db8::1]:443/x"))
    }

    @Test
    fun expandAddsWwwOnlyForApex() {
        assertEquals(
            setOf("example.com", "www.example.com"),
            HostExclusion.expandNames("https://example.com/news"),
        )
        assertEquals(
            setOf("www.example.com", "example.com"),
            HostExclusion.expandNames("www.example.com"),
        )
        assertEquals(
            setOf("news.example.com"),
            HostExclusion.expandNames("news.example.com"),
        )
    }

    @Test
    fun literalIpIsNotExpanded() {
        assertEquals(setOf("1.2.3.4"), HostExclusion.expandNames("1.2.3.4"))
    }

    @Test
    fun routesUnionWwwAndIpv4Ipv6() {
        val v4 = InetAddress.getByName("93.184.216.34")
        val v6 = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")
        val routes = HostExclusion.routesFor(setOf("example.com")) { name ->
            when (name) {
                "example.com" -> listOf(v4)
                "www.example.com" -> listOf(v6)
                else -> emptyList()
            }
        }
        val keys = routes.map { it.key }.toSet()
        assertTrue(keys.any { it.endsWith("/32") && it.startsWith("93.184.216.34") })
        assertTrue(keys.any { it.endsWith("/128") })
        assertEquals(32, routes.first { it.address.hostAddress?.startsWith("93.") == true }.prefixLength)
        assertEquals(128, routes.first { it.address is java.net.Inet6Address }.prefixLength)
        val ipv4Only = HostExclusion.ipv4RoutesFor(setOf("example.com")) { name ->
            when (name) {
                "example.com" -> listOf(v4)
                "www.example.com" -> listOf(v6)
                else -> emptyList()
            }
        }
        assertEquals(listOf("93.184.216.34/32"), ipv4Only.map { it.key })
    }

    @Test
    fun skipsLoopbackKeepsPrivateLan() {
        val routes = HostExclusion.routesFor(setOf("local")) { _ ->
            listOf(
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("10.0.0.1"),
                InetAddress.getByName("8.8.8.8"),
            )
        }
        assertEquals(setOf("10.0.0.1/32", "8.8.8.8/32"), routes.map { it.key }.toSet())
    }

    @Test
    fun loopbackIsNotExcludable() {
        assertFalse(HostExclusion.isExcludable(InetAddress.getByName("127.0.0.1")))
        assertTrue(HostExclusion.isExcludable(InetAddress.getByName("1.1.1.1")))
    }
}
