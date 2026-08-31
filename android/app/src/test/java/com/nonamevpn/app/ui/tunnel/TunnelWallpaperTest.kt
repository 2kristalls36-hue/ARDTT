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

    @Test
    fun fieldIsFirstEnumEntryAndMustNotBeUsedAsPlaceholder() {
        assertEquals(TunnelWallpaperScene.Field, TunnelWallpaperScene.entries.first())
    }

    @Test
    fun initForProcessDoesNotRerollTheScene() {
        TunnelWallpaperSession.resetForTests()
        TunnelWallpaperSession.initForProcess()
        val first = TunnelWallpaperSession.scene
        repeat(8) {
            TunnelWallpaperSession.initForProcess()
            assertEquals(first, TunnelWallpaperSession.currentOrPick())
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
