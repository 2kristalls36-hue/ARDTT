package com.nonamevpn.app.ui.tunnel

import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.NetworkClass
import com.nonamevpn.app.core.VpnPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelWallpaperTest {
    @Test
    fun lightThemeUsesDayAndDarkThemeUsesNight() {
        assertEquals(
            TunnelWallpaperTime.Day,
            resolveTunnelWallpaperTime(bypass = false, darkTheme = false),
        )
        assertEquals(
            TunnelWallpaperTime.Night,
            resolveTunnelWallpaperTime(bypass = false, darkTheme = true),
        )
    }

    @Test
    fun bypassUsesEveningRegardlessOfTheme() {
        TunnelWallpaperScene.all.forEach { scene ->
            val light = resolveTunnelWallpaper(scene, bypass = true, darkTheme = false)
            val dark = resolveTunnelWallpaper(scene, bypass = true, darkTheme = true)
            assertEquals(scene, light.scene)
            assertEquals(scene, dark.scene)
            assertEquals(TunnelWallpaperTime.Evening, light.time)
            assertEquals(TunnelWallpaperTime.Evening, dark.time)
        }
    }

    @Test
    fun everySceneHasDayNightAndEvening() {
        TunnelWallpaperScene.all.forEach { scene ->
            listOf(false to false, false to true, true to false, true to true).forEach { (bypass, dark) ->
                val wallpaper = resolveTunnelWallpaper(scene, bypass = bypass, darkTheme = dark)
                assertEquals(scene, wallpaper.scene)
                val expected = when {
                    bypass -> TunnelWallpaperTime.Evening
                    dark -> TunnelWallpaperTime.Night
                    else -> TunnelWallpaperTime.Day
                }
                assertEquals(expected, wallpaper.time)
            }
        }
    }

    @Test
    fun forcedBypassModeAlwaysEvening() {
        assertTrue(
            wallpaperBypassActive(pathMode = ConnPathMode.Bypass, networkClass = NetworkClass.DirectOk),
        )
        assertTrue(
            wallpaperBypassActive(pathMode = ConnPathMode.Bypass, activePath = VpnPath.Direct),
        )
    }

    @Test
    fun forcedDirectModeNeverEvening() {
        assertFalse(
            wallpaperBypassActive(
                pathMode = ConnPathMode.Direct,
                activePath = VpnPath.Bypass,
                networkClass = NetworkClass.NeedBypass,
            ),
        )
    }

    @Test
    fun autoModeEveningWhenProbeNeedsBypass() {
        assertTrue(
            wallpaperBypassActive(
                pathMode = ConnPathMode.Auto,
                networkClass = NetworkClass.NeedBypass,
            ),
        )
        assertTrue(
            wallpaperBypassActive(
                pathMode = ConnPathMode.Auto,
                networkClass = NetworkClass.OpenNeedBypass,
            ),
        )
        assertTrue(
            wallpaperBypassActive(
                pathMode = ConnPathMode.Auto,
                activePath = VpnPath.Bypass,
                networkClass = NetworkClass.DirectOk,
            ),
        )
        assertFalse(
            wallpaperBypassActive(
                pathMode = ConnPathMode.Auto,
                activePath = VpnPath.Direct,
                networkClass = NetworkClass.DirectOk,
            ),
        )
        assertFalse(
            wallpaperBypassActive(pathMode = ConnPathMode.Auto),
        )
    }

    @Test
    fun visibleOnEveryTabInUserModeOnly() {
        assertTrue(tunnelWallpaperVisible(admin = false))
        assertFalse(tunnelWallpaperVisible(admin = true))
    }

    @Test
    fun classicAppearanceRestoresGradientInUserMode() {
        assertFalse(
            tunnelWallpaperVisible(admin = false, classicAppearance = true),
        )
        assertTrue(
            tunnelWallpaperVisible(admin = false, classicAppearance = false),
        )
        assertFalse(
            tunnelWallpaperVisible(admin = true, classicAppearance = false),
        )
    }

    @Test
    fun tunnelTabUsesAdminChromeWhenAdminEvenBeforeLocalCollect() {
        assertEquals(
            TunnelSessionChrome.Admin,
            tunnelSessionChrome(admin = true, classicAppearance = false),
        )
        assertEquals(
            TunnelSessionChrome.Admin,
            tunnelSessionChrome(admin = true, classicAppearance = true),
        )
        assertEquals(
            TunnelSessionChrome.User,
            tunnelSessionChrome(admin = false, classicAppearance = false),
        )
        assertEquals(
            TunnelSessionChrome.Admin,
            tunnelSessionChrome(admin = false, classicAppearance = true),
        )
    }

    @Test
    fun randomSceneAlwaysReturnsOneOfThreeScenes() {
        repeat(30) {
            assertTrue(TunnelWallpaperScene.random() in TunnelWallpaperScene.all)
        }
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
