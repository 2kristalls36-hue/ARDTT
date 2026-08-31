package com.nonamevpn.app.ui.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelWallpaperTest {
    @Test
    fun allScenesHaveDayNightAndSunsetVariants() {
        TunnelWallpaperScene.all.forEach { scene ->
            listOf(false to false, true to false, false to true, true to true).forEach { (bs, dark) ->
                val wallpaper = TunnelWallpaper.resolve(scene, whitelistMode = bs, darkTheme = dark)
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
    fun whitelistModeForcesSunsetTime() {
        TunnelWallpaperScene.all.forEach { scene ->
            val wallpaper = TunnelWallpaper.resolve(scene, whitelistMode = true, darkTheme = false)
            assertTrue(wallpaper.name.endsWith("Sunset"))
        }
    }
}
