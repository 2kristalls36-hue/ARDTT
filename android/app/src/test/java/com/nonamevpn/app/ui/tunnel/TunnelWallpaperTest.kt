package com.nonamevpn.app.ui.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelWallpaperTest {
    @Test
    fun allScenesHaveDayNightAndSunsetVariants() {
        TunnelWallpaperScene.all.forEach { scene ->
            listOf(false to false, true to false, false to true, true to true).forEach { (bs, dark) ->
                val wallpaper = resolveTunnelWallpaper(scene, whitelistMode = bs, darkTheme = dark)
                assertEquals(scene, wallpaper.scene)
            }
        }
    }

    @Test
    fun randomSceneAlwaysReturnsOneOfThreeScenes() {
        repeat(30) {
            assertTrue(TunnelWallpaperScene.random() in TunnelWallpaperScene.all)
        }
    }

    @Test
    fun lightThemeUsesDayAndDarkThemeUsesNight() {
        assertEquals(
            TunnelWallpaperTime.Day,
            resolveTunnelWallpaperTime(whitelistMode = false, darkTheme = false),
        )
        assertEquals(
            TunnelWallpaperTime.Night,
            resolveTunnelWallpaperTime(whitelistMode = false, darkTheme = true),
        )
    }

    @Test
    fun whitelistModeForcesSunsetOverTheme() {
        TunnelWallpaperScene.all.forEach { scene ->
            val light = resolveTunnelWallpaper(scene, whitelistMode = true, darkTheme = false)
            val dark = resolveTunnelWallpaper(scene, whitelistMode = true, darkTheme = true)
            assertEquals(TunnelWallpaperTime.Sunset, light.time)
            assertEquals(TunnelWallpaperTime.Sunset, dark.time)
        }
    }

    @Test
    fun visibleOnlyOnTunnelTabInUserMode() {
        assertTrue(tunnelWallpaperVisible(admin = false, onTunnelTab = true))
        assertFalse(tunnelWallpaperVisible(admin = false, onTunnelTab = false))
        assertFalse(tunnelWallpaperVisible(admin = true, onTunnelTab = true))
        assertFalse(tunnelWallpaperVisible(admin = true, onTunnelTab = false))
    }
}
