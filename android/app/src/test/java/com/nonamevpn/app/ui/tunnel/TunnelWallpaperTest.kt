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

    @Test
    fun visibleOnEveryTabInUserModeOnly() {
        assertTrue(tunnelWallpaperVisible(admin = false))
        assertFalse(tunnelWallpaperVisible(admin = true))
    }

    @Test
    fun sessionKeepsTheSameSceneAfterInit() {
        TunnelWallpaperSession.resetForTests()
        TunnelWallpaperSession.initForProcess(TunnelWallpaperScene.City)
        repeat(12) {
            assertEquals(TunnelWallpaperScene.City, TunnelWallpaperSession.currentOrPick())
        }
    }

    @Test
    fun thirdLaunchAfterFieldAndCityIsRefinery() {
        val (scene, seen) = nextTunnelWallpaperScene(
            previousName = "City",
            seenNames = setOf("Field", "City"),
            randomInt = { 0 },
        )
        assertEquals(TunnelWallpaperScene.Refinery, scene)
        assertEquals(setOf("Field", "City", "Refinery"), seen)
    }

    @Test
    fun afterAllThreeSeenNextCycleDoesNotRepeatImmediately() {
        val (scene, seen) = nextTunnelWallpaperScene(
            previousName = "Refinery",
            seenNames = setOf("Field", "City", "Refinery"),
            randomInt = { 0 },
        )
        assertTrue(scene != TunnelWallpaperScene.Refinery)
        assertEquals(setOf(scene.name), seen)
    }

    @Test
    fun threeSequentialPicksCoverEveryScene() {
        var previous: String? = null
        var seen = emptySet<String>()
        val picked = mutableSetOf<TunnelWallpaperScene>()
        repeat(3) {
            val (scene, nextSeen) = nextTunnelWallpaperScene(previous, seen)
            picked.add(scene)
            previous = scene.name
            seen = nextSeen
        }
        assertEquals(TunnelWallpaperScene.all.toSet(), picked)
    }
}
