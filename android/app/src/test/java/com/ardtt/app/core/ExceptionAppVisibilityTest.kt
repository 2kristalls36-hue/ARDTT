package com.ardtt.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionAppVisibilityTest {
    @Test
    fun chromeSystemPackageWithLauncherIsVisible() {
        val facing = ExceptionAppVisibility.isUserFacing(
            packageName = "com.android.chrome",
            hasLauncher = true,
            httpsHandlerPackages = emptySet(),
        )
        assertTrue(facing)
        assertFalse(ExceptionAppVisibility.hideByDefault(systemPackage = true, userFacing = facing))
    }

    @Test
    fun chromeKnownEvenWithoutLauncherFlag() {
        val facing = ExceptionAppVisibility.isUserFacing(
            packageName = "com.android.chrome",
            hasLauncher = false,
            httpsHandlerPackages = emptySet(),
        )
        assertTrue(facing)
        assertFalse(ExceptionAppVisibility.hideByDefault(systemPackage = true, userFacing = facing))
    }

    @Test
    fun httpsHandlerCountsAsUserFacing() {
        val facing = ExceptionAppVisibility.isUserFacing(
            packageName = "org.mozilla.firefox",
            hasLauncher = false,
            httpsHandlerPackages = setOf("org.mozilla.firefox"),
        )
        assertTrue(facing)
        assertFalse(ExceptionAppVisibility.hideByDefault(systemPackage = true, userFacing = facing))
    }

    @Test
    fun systemServiceWithoutUiStaysHidden() {
        val facing = ExceptionAppVisibility.isUserFacing(
            packageName = "com.android.systemui",
            hasLauncher = false,
            httpsHandlerPackages = emptySet(),
        )
        assertFalse(facing)
        assertTrue(ExceptionAppVisibility.hideByDefault(systemPackage = true, userFacing = facing))
    }

    @Test
    fun playInstalledAppIsVisibleEvenWithoutLauncher() {
        val facing = ExceptionAppVisibility.isUserFacing(
            packageName = "com.example.mail",
            hasLauncher = false,
            httpsHandlerPackages = emptySet(),
        )
        assertFalse(facing)
        assertFalse(ExceptionAppVisibility.hideByDefault(systemPackage = false, userFacing = facing))
    }
}
