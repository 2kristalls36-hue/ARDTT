package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Test

class DeployVersionCatalogTest {
    @Test
    fun resolvedExpectedPrefersNewerCatalogOverBundledFallback() {
        // Phone APK still ships FALLBACK 1.0.52 while Releases already have 1.0.53.
        assertEquals("1.0.53", DeployVersionCatalog.resolvedExpected("1.0.53", "1.0.52"))
        assertEquals("1.0.53", DeployVersionCatalog.resolvedExpected("1.0.52", "1.0.53"))
        assertEquals("1.0.53", DeployVersionCatalog.resolvedExpected(null, "1.0.53"))
        assertEquals("1.0.52", DeployVersionCatalog.resolvedExpected(null, "1.0.52"))
        assertEquals("1.0.53", DeployVersionCatalog.resolvedExpected("  ", "1.0.53"))
        assertEquals("1.0.53", DeployVersionCatalog.resolvedExpected("1.0.53", ""))
    }
}
