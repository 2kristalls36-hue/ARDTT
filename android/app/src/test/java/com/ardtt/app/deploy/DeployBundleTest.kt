package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployBundleTest {
    @Test
    fun fallbackVersionIsSemverStack() {
        assertTrue(
            "FALLBACK_VERSION must stay in lockstep with server/DEPLOY_VERSION",
            DeployBundle.FALLBACK_VERSION.matches(Regex("""\d+\.\d+\.\d+""")),
        )
    }

    @Test
    fun isCurrentRequiresExactMatch() {
        assertTrue(DeployBundle.isCurrent("1.0.12", "1.0.12"))
        assertTrue(DeployBundle.isCurrent("1.0.12", " 1.0.12 "))
        assertFalse(DeployBundle.isCurrent("1.0.5", "1.0.12"))
        assertFalse(DeployBundle.isCurrent("", "1.0.12"))
        assertFalse(DeployBundle.isCurrent(null, "1.0.12"))
        assertFalse(DeployBundle.isCurrent("1.0.12", ""))
    }

    @Test
    fun fallbackMatchesServerDeployVersion() {
        assertEquals("1.0.51", DeployBundle.FALLBACK_VERSION)
    }

    @Test
    fun assetPathMatchesPackScript() {
        assertEquals("deploy/DEPLOY_VERSION", DeployBundle.ASSET_VERSION_FILE)
    }

    @Test
    fun compareAndMaxPreferNewerSemver() {
        assertTrue(DeployBundle.compareVersions("1.0.51", "1.0.46") > 0)
        assertTrue(DeployBundle.compareVersions("1.0.9", "1.0.51") < 0)
        assertEquals(0, DeployBundle.compareVersions("1.0.51", "1.0.51"))
        assertEquals("1.0.51", DeployBundle.maxVersion("1.0.46", "1.0.51"))
        assertEquals("1.0.51", DeployBundle.maxVersion("1.0.51", "1.0.46"))
        assertEquals("1.0.51", DeployBundle.maxVersion("", "1.0.51"))
        assertEquals("1.0.51", DeployBundle.maxVersion("1.0.51", ""))
    }
}
